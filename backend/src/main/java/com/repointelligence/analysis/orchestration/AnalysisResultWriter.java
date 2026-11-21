package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.capability.CapabilityRow;
import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerCapability;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.domain.CoverageRecord;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.ModuleRecord;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.domain.TestRecord;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerCapabilityRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.DependencyResult;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.security.SecretRedactor;
import com.repointelligence.repository.domain.RepositorySnapshot;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place analysis results are written. Analyzers return plain DTOs; this class redacts,
 * bounds and fingerprints them, then stores each analyzer's results in its own transaction so one
 * analyzer's persistence problem never loses another's results.
 */
@Component
public class AnalysisResultWriter {

	private static final int TITLE_MAX = 500;

	private static final int PATH_MAX = 1024;

	private static final int ERROR_MAX = 1000;

	private final EntityManager entityManager;

	private final AnalyzerExecutionRepository executions;

	private final AnalyzerCapabilityRepository capabilities;

	private final AnalysisResultProperties properties;

	public AnalysisResultWriter(EntityManager entityManager, AnalyzerExecutionRepository executions,
			AnalyzerCapabilityRepository capabilities, AnalysisResultProperties properties) {
		this.entityManager = entityManager;
		this.executions = executions;
		this.capabilities = capabilities;
		this.properties = properties;
	}

	@Transactional
	public void saveTechnologies(UUID snapshotId, UUID runId, List<DetectedTechnology> technologies) {
		RepositorySnapshot snapshot = entityManager.getReference(RepositorySnapshot.class, snapshotId);
		AnalysisRun run = entityManager.getReference(AnalysisRun.class, runId);
		for (DetectedTechnology detected : technologies) {
			TechnologyFinding finding = new TechnologyFinding(snapshot, run, detected.technology(), detected.kind(),
					detected.confidence(), detected.detectorId() != null ? detected.detectorId() : "unknown");
			finding.setVersion(detected.version());
			finding.setEvidence(detected.evidence());
			finding.setSharePercent(detected.sharePercent());
			entityManager.persist(finding);
		}
	}

	/** Replaces the capability matrix of a run (placeholders at the start, real statuses at the end). */
	@Transactional
	public void replaceCapabilities(UUID snapshotId, UUID runId, List<CapabilityRow> rows) {
		capabilities.deleteByRunId(runId);
		RepositorySnapshot snapshot = entityManager.getReference(RepositorySnapshot.class, snapshotId);
		AnalysisRun run = entityManager.getReference(AnalysisRun.class, runId);
		for (CapabilityRow row : rows) {
			AnalyzerCapability capability = new AnalyzerCapability(snapshot, run, row.technology(), row.category(),
					row.capabilityLevel(), row.executionStatus());
			capability.setAnalyzerId(row.analyzerId());
			capability.setAnalyzerVersion(row.analyzerVersion());
			capability.setLimitationReason(row.limitationReason());
			entityManager.persist(capability);
		}
	}

	/** Creates the execution row of an analyzer: pending, unavailable or not configured. */
	@Transactional
	public void recordExecution(UUID snapshotId, UUID runId, AnalyzerDescriptor descriptor, ExecutionStatus status,
			String reason) {
		AnalyzerExecution execution = newExecution(snapshotId, runId, descriptor, status);
		execution.setErrorSummary(truncate(reason, ERROR_MAX));
		entityManager.persist(execution);
	}

	/** Stores a successful analyzer's results and finishes its execution row, atomically. */
	@Transactional
	public void saveCompleted(UUID snapshotId, UUID runId, AnalyzerDescriptor descriptor, Instant started,
			Instant completed, AnalyzerResult result) {
		RepositorySnapshot snapshot = entityManager.getReference(RepositorySnapshot.class, snapshotId);
		AnalysisRun run = entityManager.getReference(AnalysisRun.class, runId);
		String analyzerId = descriptor.id();
		List<String> limitations = new ArrayList<>(result.limitations());

		List<FindingResult> findings = result.findings();
		if (findings.size() > properties.maxFindingsPerAnalyzer()) {
			limitations.add("Only the first " + properties.maxFindingsPerAnalyzer() + " of " + findings.size()
					+ " findings were stored.");
			findings = findings.subList(0, properties.maxFindingsPerAnalyzer());
		}
		for (FindingResult finding : findings) {
			entityManager.persist(toFinding(snapshot, run, analyzerId, finding));
		}
		for (DependencyResult dependency : result.dependencies()) {
			DependencyRecord record = new DependencyRecord(snapshot, run, analyzerId, dependency.ecosystem(),
					dependency.manifestPath(), dependency.name(), dependency.versionSource());
			record.setModulePath(dependency.modulePath());
			record.setNamespace(dependency.namespace());
			record.setDeclaredVersion(dependency.declaredVersion());
			record.setResolvedVersion(dependency.resolvedVersion());
			record.setScope(dependency.scope());
			record.setDirect(dependency.direct());
			record.setManagementEntry(dependency.managementEntry());
			record.setVulnerabilityStatus(dependency.vulnerabilityStatus());
			record.setVulnerabilityIds(dependency.vulnerabilityIds().isEmpty() ? null : dependency.vulnerabilityIds());
			record.setNote(truncate(dependency.note(), 512));
			entityManager.persist(record);
		}
		result.modules().forEach(module -> {
			ModuleRecord record = new ModuleRecord(snapshot, run, module.moduleName(), module.path());
			record.setBuildFilePath(module.buildFilePath());
			record.setEcosystem(module.ecosystem());
			record.setDetectedResponsibility(module.detectedResponsibility());
			record.setEvidence(module.evidence());
			entityManager.persist(record);
		});
		result.testRecords().forEach(test -> {
			TestRecord record = new TestRecord(snapshot, run, test.testFilePath());
			record.setTestSymbol(test.testSymbol());
			record.setTestMethodCount(test.testMethodCount());
			record.setFramework(test.framework());
			record.setProductionSymbol(test.productionSymbol());
			record.setMappingMethod(test.mappingMethod());
			record.setConfidence(test.confidence());
			record.setEvidence(test.evidence());
			entityManager.persist(record);
		});
		result.coverageRecords().forEach(coverage -> {
			CoverageRecord record = new CoverageRecord(snapshot, run, coverage.reportPath(), coverage.scope(),
					coverage.name());
			record.setLineCovered(coverage.lineCovered());
			record.setLineMissed(coverage.lineMissed());
			record.setBranchCovered(coverage.branchCovered());
			record.setBranchMissed(coverage.branchMissed());
			entityManager.persist(record);
		});
		// Large graphs: flushes and clears the session in batches, so it comes last.
		limitations.addAll(new ArchitectureResultWriter(entityManager, this::redactMap, snapshotId, runId)
			.write(result.architecture()));

		AnalyzerExecution execution = executions.findByRunIdAndAnalyzerId(runId, analyzerId)
			.orElseGet(() -> newExecution(snapshotId, runId, descriptor, result.status()));
		execution.setStatus(result.status());
		execution.setStartedAt(started);
		execution.setCompletedAt(completed);
		execution.setDurationMs(Math.max(0, completed.toEpochMilli() - started.toEpochMilli()));
		execution.setConfigSummary(redactMap(result.configSummary()));
		execution.setLimitations(limitations.stream().map(SecretRedactor::redact).toList());
		execution.setErrorSummary(result.warnings().isEmpty() ? null
				: truncate(SecretRedactor.redact(String.join("; ", result.warnings())), ERROR_MAX));
		entityManager.persist(execution);
	}

	/** Finishes an execution row that has no result to store: a detector, or an analyzer that failed, timed out or was cancelled. */
	@Transactional
	public void finishExecution(UUID snapshotId, UUID runId, AnalyzerDescriptor descriptor, ExecutionStatus status,
			Instant started, Instant completed, String errorSummary) {
		AnalyzerExecution execution = executions.findByRunIdAndAnalyzerId(runId, descriptor.id())
			.orElseGet(() -> newExecution(snapshotId, runId, descriptor, status));
		execution.setStatus(status);
		execution.setStartedAt(started);
		execution.setCompletedAt(completed);
		execution.setDurationMs(started == null || completed == null ? null
				: Math.max(0, completed.toEpochMilli() - started.toEpochMilli()));
		execution.setErrorSummary(truncate(SecretRedactor.redact(errorSummary), ERROR_MAX));
		entityManager.persist(execution);
	}

	private AnalyzerExecution newExecution(UUID snapshotId, UUID runId, AnalyzerDescriptor descriptor,
			ExecutionStatus status) {
		return new AnalyzerExecution(entityManager.getReference(AnalysisRun.class, runId),
				entityManager.getReference(RepositorySnapshot.class, snapshotId), descriptor.id(), descriptor.version(),
				descriptor.category(), status);
	}

	private AnalysisFinding toFinding(RepositorySnapshot snapshot, AnalysisRun run, String analyzerId,
			FindingResult result) {
		String filePath = truncate(result.filePath(), PATH_MAX);
		AnalysisFinding finding = new AnalysisFinding(snapshot, run, analyzerId, result.category(),
				truncate(result.ruleId(), 255), result.severity(), result.confidence(),
				truncate(SecretRedactor.redact(result.title()), TITLE_MAX), result.analysisMethod(),
				fingerprint(analyzerId, result, filePath));
		finding.setDescription(SecretRedactor.redact(result.description()));
		finding.setFilePath(filePath);
		finding.setModulePath(truncate(result.modulePath(), PATH_MAX));
		finding.setStartLine(result.startLine());
		finding.setEndLine(result.endLine());
		finding.setEvidence(redactMap(result.evidence()));
		finding.setSuggestedNextStep(SecretRedactor.redact(result.suggestedNextStep()));
		return finding;
	}

	/**
	 * Stable hash used to group the same issue reported by different analyzers: category, file and
	 * lines when the finding has a location (so a secret scanner and Semgrep flagging the same spot
	 * share a fingerprint), otherwise analyzer, rule and title.
	 */
	static String fingerprint(String analyzerId, FindingResult result, String filePath) {
		String basis = filePath != null
				? result.category() + "|" + filePath + "|" + result.startLine() + "|" + result.endLine()
				: result.category() + "|" + analyzerId + "|" + result.ruleId() + "|" + result.title();
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Redacts every string and caps its length, recursively. Raw secrets never reach the database. */
	Map<String, Object> redactMap(Map<String, Object> map) {
		if (map == null || map.isEmpty()) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		map.forEach((key, value) -> result.put(key, redactValue(value)));
		return result;
	}

	@SuppressWarnings("unchecked")
	private Object redactValue(Object value) {
		if (value instanceof String text) {
			return truncate(SecretRedactor.redact(text), properties.maxExcerptLength());
		}
		if (value instanceof Map<?, ?> map) {
			return redactMap((Map<String, Object>) map);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(this::redactValue).toList();
		}
		return value;
	}

	private static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max);
	}


	/** After an unexpected failure: executions still pending will never run. */
	@Transactional
	public void failPending(UUID runId, String reason) {
		for (AnalyzerExecution execution : executions.findByRunId(runId)) {
			if (execution.getStatus() == ExecutionStatus.NOT_ANALYZED) {
				execution.setStatus(ExecutionStatus.FAILED);
				execution.setErrorSummary(reason);
			}
		}
	}

}
