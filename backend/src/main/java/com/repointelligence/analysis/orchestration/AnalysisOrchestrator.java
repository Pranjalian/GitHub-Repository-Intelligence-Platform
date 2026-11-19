package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.capability.CapabilityMatrixBuilder;
import com.repointelligence.analysis.capability.CapabilityRow;
import com.repointelligence.analysis.capability.ExecutionOutcome;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.registry.AnalyzerRegistry;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CancellationSource;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.security.SecretRedactor;
import com.repointelligence.common.validation.GitHubRepositoryRef;
import com.repointelligence.repository.application.IngestionResult;
import com.repointelligence.repository.application.IngestionService;
import com.repointelligence.repository.domain.FileRecord;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.infrastructure.persistence.FileRecordRepository;
import com.repointelligence.repository.infrastructure.storage.WorkspaceManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one analysis: ingestion, technology detection, analyzer selection and isolated analyzer
 * execution, then derives the run status. Every detector and analyzer is fault-isolated and bounded
 * by the run deadline, results are stored per analyzer, and the workspace is released at the end.
 * {@link #execute} never throws: any failure ends in a terminal run status.
 */
@Service
public class AnalysisOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

	private final AnalysisJobProperties jobProperties;

	private final Clock clock;

	private final IngestionService ingestion;

	private final WorkspaceManager workspaces;

	private final AnalyzerRegistry registry;

	private final CapabilityMatrixBuilder matrixBuilder;

	private final AnalyzerSettings settings;

	private final AnalysisResultWriter writer;

	private final RunCancellationRegistry cancellations;

	private final IsolatedRunner runner;

	private final AnalysisRunRepository runs;

	private final FileRecordRepository files;

	private final TransactionTemplate transaction;

	public AnalysisOrchestrator(AnalysisJobProperties jobProperties, Clock clock, IngestionService ingestion,
			WorkspaceManager workspaces, AnalyzerRegistry registry, CapabilityMatrixBuilder matrixBuilder,
			AnalyzerSettings settings, AnalysisResultWriter writer, RunCancellationRegistry cancellations,
			IsolatedRunner runner, AnalysisRunRepository runs, FileRecordRepository files,
			PlatformTransactionManager transactionManager) {
		this.jobProperties = jobProperties;
		this.clock = clock;
		this.ingestion = ingestion;
		this.workspaces = workspaces;
		this.registry = registry;
		this.matrixBuilder = matrixBuilder;
		this.settings = settings;
		this.writer = writer;
		this.cancellations = cancellations;
		this.runner = runner;
		this.runs = runs;
		this.files = files;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	private record RunInfo(UUID snapshotId, GitHubRepositoryRef repositoryRef, String commitSha) {
	}

	public void execute(UUID runId) {
		// Registered before the run is claimed, so a cancel request can never miss a running job.
		CancellationSource cancel = cancellations.register(runId);
		UUID snapshotId = null;
		try {
			MDC.put("runId", runId.toString());
			Integer claimed = transaction.execute(status -> runs.claimQueued(runId, clock.instant()));
			if (claimed == null || claimed == 0) {
				log.info("Run {} is no longer queued (cancelled or already taken); skipping", runId);
				return;
			}
			RunInfo info = transaction.execute(status -> loadRunInfo(runId));
			snapshotId = info.snapshotId();
			MDC.put("snapshotId", snapshotId.toString());
			Instant deadline = clock.instant().plus(jobProperties.maxAnalysisDuration());

			IngestionResult ingested = ingestion.ingest(runId);
			if (!ingested.succeeded()) {
				log.info("Ingestion ended with {}: {}", ingested.status(), ingested.failureReason());
				return;
			}
			if (cancel.isCancelled()) {
				finishRun(runId, RunStatus.CANCELLED, null);
				return;
			}
			analyze(runId, info, deadline, cancel);
		}
		catch (RuntimeException ex) {
			log.error("Run {} failed unexpectedly", runId, ex);
			failUnexpectedly(runId, ex);
		}
		finally {
			cancellations.remove(runId);
			if (snapshotId != null) {
				try {
					workspaces.releaseAfterRun(snapshotId);
				}
				catch (RuntimeException ex) {
					log.warn("Could not release the workspace of snapshot {}", snapshotId);
				}
			}
			MDC.clear();
		}
	}

	private void analyze(UUID runId, RunInfo info, Instant deadline, CancellationSource cancel) {
		UUID snapshotId = info.snapshotId();
		setStatus(runId, RunStatus.ANALYZING);
		List<FileInfo> fileInfos = transaction.execute(status -> files.findBySnapshotId(snapshotId)
			.stream()
			.map(AnalysisOrchestrator::toFileInfo)
			.toList());
		AnalysisContext detectionContext = new AnalysisContext(info.repositoryRef(), snapshotId, runId,
				info.commitSha(), workspaces.view(snapshotId), fileInfos, List.of(), settings, deadline, cancel);

		// 1. technology detection, each detector isolated
		List<ExecutionStatus> ran = new ArrayList<>();
		List<DetectedTechnology> detected = detect(detectionContext, ran);
		if (cancel.isCancelled()) {
			finishRun(runId, RunStatus.CANCELLED, null);
			return;
		}
		List<DetectedTechnology> technologies = TechnologyMerger.merge(detected);
		writer.saveTechnologies(snapshotId, runId, technologies);

		// 2. which analyzers apply, and the initial capability matrix
		AnalysisContext context = detectionContext.withTechnologies(technologies);
		Map<String, Applicability> applicability = registry.evaluate(context);
		List<CapabilityRow> matrix = matrixBuilder.build(technologies, applicability);
		writer.replaceCapabilities(snapshotId, runId, matrix);
		List<Analyzer> runnable = new ArrayList<>();
		int unavailable = 0;
		for (Analyzer analyzer : registry.analyzers()) {
			AnalyzerDescriptor descriptor = analyzer.descriptor();
			Applicability result = applicability.get(descriptor.id());
			switch (result.kind()) {
				case APPLICABLE -> {
					writer.recordExecution(snapshotId, runId, descriptor, ExecutionStatus.NOT_ANALYZED, null);
					runnable.add(analyzer);
				}
				case UNAVAILABLE -> {
					writer.recordExecution(snapshotId, runId, descriptor, ExecutionStatus.UNAVAILABLE, result.reason());
					unavailable++;
				}
				case DISABLED -> writer.recordExecution(snapshotId, runId, descriptor, ExecutionStatus.NOT_CONFIGURED,
						result.reason());
				case NOT_APPLICABLE -> {
					// nothing to analyze: no row at all
				}
			}
		}

		// 3. analyzers, isolated, results stored as each one finishes
		Map<String, ExecutionOutcome> outcomes = new HashMap<>();
		runAnalyzers(context, runnable, deadline, cancel, ran, outcomes);

		// 4. final matrix and run status
		boolean cancelled = cancel.isCancelled();
		writer.replaceCapabilities(snapshotId, runId, matrixBuilder.applyOutcomes(matrix, outcomes));
		RunStatus status = RunStatusCalculator.calculate(ran, unavailable, cancelled);
		String reason = status == RunStatus.FAILED ? "None of the analyzers completed successfully." : null;
		finishRun(runId, status, reason);
	}

	private List<DetectedTechnology> detect(AnalysisContext context, List<ExecutionStatus> ran) {
		Map<String, TechnologyDetector> byId = new LinkedHashMap<>();
		registry.detectors().forEach(detector -> byId.put(detector.descriptor().id(), detector));
		List<IsolatedRunner.Task<List<DetectedTechnology>>> tasks = new ArrayList<>();
		for (TechnologyDetector detector : registry.detectors()) {
			String id = detector.descriptor().id();
			if (!settings.isEnabled(id)) {
				writer.recordExecution(context.snapshotId(), context.runId(), detector.descriptor(),
						ExecutionStatus.NOT_CONFIGURED, "disabled by configuration");
				continue;
			}
			tasks.add(new IsolatedRunner.Task<>(id, token -> detector.detect(context.withCancellation(token))));
		}
		List<DetectedTechnology> all = new ArrayList<>();
		runner.runAll(tasks, context.deadline(), context.cancellation(), outcome -> {
			AnalyzerDescriptor descriptor = byId.get(outcome.id()).descriptor();
			ran.add(outcome.status());
			if (outcome.status() == ExecutionStatus.COMPLETED && outcome.value() != null) {
				outcome.value()
					.stream()
					.map(t -> t.detectorId() != null ? t : new DetectedTechnology(t.technology(), t.kind(), t.version(),
							t.confidence(), t.evidence(), outcome.id(), t.sharePercent()))
					.forEach(all::add);
			}
			store(() -> writer.finishExecution(context.snapshotId(), context.runId(), descriptor, outcome.status(),
					outcome.startedAt(), outcome.completedAt(), outcome.errorSummary()));
		});
		return all;
	}

	private void runAnalyzers(AnalysisContext context, List<Analyzer> runnable, Instant deadline,
			CancellationSource cancel, List<ExecutionStatus> ran, Map<String, ExecutionOutcome> outcomes) {
		Map<String, Analyzer> byId = new HashMap<>();
		List<IsolatedRunner.Task<AnalyzerResult>> tasks = new ArrayList<>();
		for (Analyzer analyzer : runnable) {
			String id = analyzer.descriptor().id();
			byId.put(id, analyzer);
			tasks.add(new IsolatedRunner.Task<>(id, token -> {
				AnalyzerResult result = analyzer.analyze(context.withCancellation(token));
				if (result == null) {
					throw new IllegalStateException("the analyzer returned no result");
				}
				return result;
			}));
		}
		runner.runAll(tasks, deadline, cancel, outcome -> {
			AnalyzerDescriptor descriptor = byId.get(outcome.id()).descriptor();
			ExecutionStatus finalStatus = outcome.status();
			String reason = outcome.errorSummary();
			if (outcome.status() == ExecutionStatus.COMPLETED) {
				AnalyzerResult result = outcome.value();
				finalStatus = result.status();
				try {
					writer.saveCompleted(context.snapshotId(), context.runId(), descriptor, outcome.startedAt(),
							outcome.completedAt(), result);
					reason = result.warnings().isEmpty() ? null : String.join("; ", result.warnings());
				}
				catch (RuntimeException ex) {
					// Storing one analyzer's results failed; the others are unaffected.
					log.error("Could not store the results of {}", outcome.id(), ex);
					finalStatus = ExecutionStatus.FAILED;
					reason = "Results could not be stored (" + ex.getClass().getSimpleName() + ").";
					ExecutionStatus failed = finalStatus;
					String failedReason = reason;
					store(() -> writer.finishExecution(context.snapshotId(), context.runId(), descriptor, failed,
							outcome.startedAt(), outcome.completedAt(), failedReason));
				}
			}
			else {
				store(() -> writer.finishExecution(context.snapshotId(), context.runId(), descriptor, outcome.status(),
						outcome.startedAt(), outcome.completedAt(), outcome.errorSummary()));
			}
			ran.add(finalStatus);
			outcomes.put(outcome.id(), new ExecutionOutcome(finalStatus, reason));
		});
	}

	private static void store(Runnable action) {
		try {
			action.run();
		}
		catch (RuntimeException ex) {
			log.error("Could not record an execution", ex);
		}
	}

	private RunInfo loadRunInfo(UUID runId) {
		AnalysisRun run = runs.findById(runId).orElseThrow();
		RepositorySnapshot snapshot = run.getSnapshot();
		var repository = snapshot.getRepository();
		return new RunInfo(snapshot.getId(),
				new GitHubRepositoryRef(repository.getOwner(), repository.getName(), snapshot.getBranch()),
				snapshot.getCommitSha());
	}

	private void setStatus(UUID runId, RunStatus status) {
		transaction.executeWithoutResult(tx -> runs.findById(runId).orElseThrow().setStatus(status));
	}

	private void finishRun(UUID runId, RunStatus status, String failureReason) {
		transaction.executeWithoutResult(tx -> {
			AnalysisRun run = runs.findById(runId).orElseThrow();
			run.setStatus(status);
			run.setFailureReason(failureReason);
			run.setCompletedAt(clock.instant());
		});
	}

	/** Last resort: whatever went wrong, the run must not stay "running". */
	private void failUnexpectedly(UUID runId, RuntimeException cause) {
		try {
			String reason = "Analysis failed unexpectedly (" + cause.getClass().getSimpleName() + ").";
			writer.failPending(runId, SecretRedactor.redact(reason));
			finishRun(runId, RunStatus.FAILED, reason);
		}
		catch (RuntimeException ex) {
			log.error("Could not mark run {} as failed", runId, ex);
		}
	}

	private static FileInfo toFileInfo(FileRecord record) {
		return new FileInfo(record.getPath(), record.getLanguage(), record.getFileType(), record.getSizeBytes(),
				record.getContentHash(), record.isBinary(), record.getSkippedReason());
	}

}
