package com.repointelligence.analysis.application;

import com.repointelligence.analysis.api.AnalysisRunDTO;
import com.repointelligence.analysis.api.ExecutionDTO;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.orchestration.RunCancellationRegistry;
import com.repointelligence.common.exception.ConflictException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.domain.RepositorySnapshot;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Status and cancellation of analysis runs. */
@Service
public class AnalysisRunService {

	private static final Set<RunStatus> TERMINAL = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_WARNINGS,
			RunStatus.FAILED, RunStatus.CANCELLED);

	private final AnalysisRunRepository runs;

	private final AnalyzerExecutionRepository executions;

	private final RunCancellationRegistry cancellations;

	private final Clock clock;

	private final TransactionTemplate transaction;

	public AnalysisRunService(AnalysisRunRepository runs, AnalyzerExecutionRepository executions,
			RunCancellationRegistry cancellations, Clock clock, PlatformTransactionManager transactionManager) {
		this.runs = runs;
		this.executions = executions;
		this.cancellations = cancellations;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Transactional(readOnly = true)
	public AnalysisRunDTO get(UUID runId) {
		return toDto(runs.findById(runId).orElseThrow(AnalysisRunService::notFound));
	}

	/**
	 * Requests cancellation. A run that has not started is cancelled immediately; a running one is
	 * signalled and ends as CANCELLED shortly after, keeping the results of analyzers that already
	 * finished. A run that has already ended cannot be cancelled (409).
	 */
	public AnalysisRunDTO cancel(UUID runId) {
		AnalysisRun existing = transaction.execute(status -> runs.findById(runId).orElseThrow(AnalysisRunService::notFound));
		if (TERMINAL.contains(existing.getStatus())) {
			throw new ConflictException("The run has already ended (" + existing.getStatus() + ").");
		}
		if (!cancellations.cancel(runId)) {
			// Not executing in this process: either still queued, or orphaned by a restart.
			Integer cancelled = transaction.execute(status -> runs.cancelQueued(runId, clock.instant()));
			if (cancelled == null || cancelled == 0) {
				transaction.executeWithoutResult(status -> {
					AnalysisRun run = runs.findById(runId).orElseThrow();
					if (!TERMINAL.contains(run.getStatus())) {
						run.setStatus(RunStatus.CANCELLED);
						run.setCompletedAt(clock.instant());
					}
				});
			}
		}
		// Not get(runId): a call within the same bean bypasses the transactional proxy, and the DTO reads lazy associations.
		return transaction.execute(status -> toDto(runs.findById(runId).orElseThrow(AnalysisRunService::notFound)));
	}

	private AnalysisRunDTO toDto(AnalysisRun run) {
		RepositorySnapshot snapshot = run.getSnapshot();
		List<ExecutionDTO> executionDtos = executions.findByRunId(run.getId())
			.stream()
			.sorted(Comparator.comparing(AnalyzerExecution::getAnalyzerId))
			.map(e -> new ExecutionDTO(e.getAnalyzerId(), e.getCategory(), e.getStatus(), e.getStartedAt(),
					e.getCompletedAt(), e.getDurationMs(), e.getErrorSummary(), reason(e)))
			.toList();
		return new AnalysisRunDTO(run.getId(), snapshot.getRepository().getId(), snapshot.getId(),
				snapshot.getCommitSha(), run.getStatus(), run.getFailureReason(), run.getRetryAfter(),
				run.getRequestedAt(), run.getStartedAt(), run.getCompletedAt(), phase(run.getStatus(), executionDtos),
				executionDtos);
	}

	private static String reason(AnalyzerExecution execution) {
		if (execution.getErrorSummary() != null && !execution.getErrorSummary().isBlank()) {
			return execution.getErrorSummary();
		}
		List<String> limitations = execution.getLimitations();
		return limitations == null || limitations.isEmpty() ? null : limitations.get(0);
	}

	private static String phase(RunStatus status, List<ExecutionDTO> executions) {
		return switch (status) {
			case QUEUED -> "QUEUED";
			case INGESTING -> "INGESTING";
			case ANALYZING -> executions.isEmpty() ? "DETECTING" : "ANALYZING";
			default -> "FINISHED";
		};
	}

	private static ResourceNotFoundException notFound() {
		return new ResourceNotFoundException("Analysis run not found.");
	}

}
