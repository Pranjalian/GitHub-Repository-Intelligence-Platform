package com.repointelligence.report.application;

import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerCapability;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.ModuleRecord;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository.SeverityCount;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerCapabilityRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.ModuleRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.CoverageRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.TestRecordRepository;
import com.repointelligence.report.api.CoverageRecordDTO;
import com.repointelligence.report.api.CoverageReportDTO;
import com.repointelligence.report.api.TestRecordDTO;
import com.repointelligence.report.api.TestsReportDTO;
import com.repointelligence.analysis.registry.AnalyzerRegistry;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.report.api.AnalyzerSummaryDTO;
import com.repointelligence.report.api.CapabilityMatrixRowDTO;
import com.repointelligence.report.api.ConsolidatedReportDTO;
import com.repointelligence.report.api.DependencyDTO;
import com.repointelligence.report.api.FindingDTO;
import com.repointelligence.report.api.LimitationDTO;
import com.repointelligence.report.api.ModuleDTO;
import com.repointelligence.report.api.ReportRunDTO;
import com.repointelligence.report.api.ReportSectionDTO;
import com.repointelligence.report.api.ReportSnapshotDTO;
import com.repointelligence.report.api.RepositoryRefDTO;
import com.repointelligence.report.api.StructureDTO;
import com.repointelligence.report.api.TechnologyDTO;
import com.repointelligence.repository.api.PageResponse;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the consolidated, commit-scoped report and serves its findings and dependencies. Every read
 * goes through {@link #resolve}: the snapshot must belong to the repository (404 otherwise) and
 * results always come from exactly one run of that snapshot.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

	private static final Set<RunStatus> COMPLETED = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_WARNINGS);

	/** Executions in these states are worth telling the reader about. */
	private static final Set<ExecutionStatus> LIMITING = Set.of(ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT,
			ExecutionStatus.CANCELLED, ExecutionStatus.UNAVAILABLE, ExecutionStatus.NOT_CONFIGURED,
			ExecutionStatus.NOT_SUPPORTED, ExecutionStatus.SKIPPED, ExecutionStatus.COMPLETED_WITH_WARNINGS);

	private static final String STRUCTURE_SUMMARY_RULE = "structure-summary";

	private static final String ENTRY_POINT_RULE = "entry-point";

	private final RepositorySnapshotRepository snapshots;

	private final AnalysisRunRepository runs;

	private final AnalyzerExecutionRepository executions;

	private final AnalyzerCapabilityRepository capabilities;

	private final TechnologyFindingRepository technologies;

	private final ModuleRecordRepository modules;

	private final AnalysisFindingRepository findings;

	private final DependencyRecordRepository dependencies;

	private final AnalyzerRegistry registry;

	private final TestRecordRepository testRecords;

	private final CoverageRecordRepository coverageRecords;

	public ReportService(RepositorySnapshotRepository snapshots, AnalysisRunRepository runs,
			AnalyzerExecutionRepository executions, AnalyzerCapabilityRepository capabilities,
			TechnologyFindingRepository technologies, ModuleRecordRepository modules,
			AnalysisFindingRepository findings, DependencyRecordRepository dependencies, AnalyzerRegistry registry,
			TestRecordRepository testRecords, CoverageRecordRepository coverageRecords) {
		this.testRecords = testRecords;
		this.coverageRecords = coverageRecords;
		this.snapshots = snapshots;
		this.runs = runs;
		this.executions = executions;
		this.capabilities = capabilities;
		this.technologies = technologies;
		this.modules = modules;
		this.findings = findings;
		this.dependencies = dependencies;
		this.registry = registry;
	}

	/**
	 * The report for a snapshot: the latest completed run unless {@code runId} names another run of
	 * that same snapshot.
	 */
	public ConsolidatedReportDTO generate(UUID repositoryId, UUID snapshotId, UUID runId) {
		Resolved resolved = resolve(repositoryId, snapshotId, runId);
		RepositorySnapshot snapshot = resolved.snapshot();
		AnalysisRun run = resolved.run();
		TrackedRepository repository = snapshot.getRepository();

		List<AnalyzerExecution> runExecutions = executions.findBySnapshotIdAndRunId(snapshotId, run.getId())
			.stream()
			.sorted(Comparator.comparing(AnalyzerExecution::getAnalyzerId))
			.toList();
		List<AnalyzerCapability> matrix = capabilities.findBySnapshotIdAndRunId(snapshotId, run.getId());
		List<AnalysisFinding> structureFindings = findings.findBySnapshotIdAndRunId(snapshotId, run.getId())
			.stream()
			.filter(f -> f.getCategory() == AnalysisCategory.STRUCTURE)
			.toList();

		return new ConsolidatedReportDTO(
				new RepositoryRefDTO(repository.getId(), repository.getOwner(), repository.getName(),
						repository.getCanonicalUrl()),
				new ReportSnapshotDTO(snapshot.getId(), snapshot.getCommitSha(), snapshot.getBranch(),
						run.getCompletedAt()),
				new ReportRunDTO(run.getId(), run.getStatus(), run.getCompletedAt()),
				technologies.findBySnapshotIdAndRunId(snapshotId, run.getId())
					.stream()
					.sorted(Comparator.comparing(TechnologyFinding::getKind).thenComparing(TechnologyFinding::getTechnology))
					.map(ReportService::toTechnology)
					.toList(),
				structure(snapshotId, run.getId(), runExecutions, structureFindings),
				matrix.stream()
					.sorted(Comparator.comparing(AnalyzerCapability::getTechnology)
						.thenComparing(AnalyzerCapability::getCategory))
					.map(ReportService::toMatrixRow)
					.toList(),
				sections(snapshotId, run.getId(), runExecutions, matrix), limitations(runExecutions, matrix));
	}

	public PageResponse<FindingDTO> findings(UUID repositoryId, UUID snapshotId, AnalysisCategory category,
			Severity severity, String module, String file, String sort, int page, int size) {
		Resolved resolved = resolve(repositoryId, snapshotId, null);
		var spec = ReportSpecifications.findings(snapshotId, resolved.run().getId(), category, severity, module, file,
				sort);
		TrackedRepository repository = resolved.snapshot().getRepository();
		String sha = resolved.snapshot().getCommitSha();
		return PageResponse.from(findings.findAll(spec, PageRequest.of(page, size)),
				f -> toFinding(f, repository, sha));
	}

	public PageResponse<DependencyDTO> dependencies(UUID repositoryId, UUID snapshotId, Ecosystem ecosystem,
			String module, String scope, Boolean vulnerable, int page, int size) {
		Resolved resolved = resolve(repositoryId, snapshotId, null);
		var spec = ReportSpecifications.dependencies(snapshotId, resolved.run().getId(), ecosystem, module, scope,
				vulnerable);
		return PageResponse.from(dependencies.findAll(spec, PageRequest.of(page, size)), ReportService::toDependency);
	}

	/** Test inventory. Counts are null when no analyzer counted tests (never 0 for "unknown"). */
	public TestsReportDTO tests(UUID repositoryId, UUID snapshotId) {
		Resolved resolved = resolve(repositoryId, snapshotId, null);
		UUID runId = resolved.run().getId();
		List<TestRecordDTO> records = testRecords.findBySnapshotIdAndRunId(snapshotId, runId)
			.stream()
			.sorted(Comparator.comparing(com.repointelligence.analysis.domain.TestRecord::getTestFilePath))
			.map(t -> new TestRecordDTO(t.getTestFilePath(), t.getTestSymbol(), t.getTestMethodCount(), t.getFramework(),
					t.getProductionSymbol(), t.getMappingMethod(), t.getConfidence()))
			.toList();
		boolean counted = records.stream().anyMatch(r -> r.testMethodCount() != null);
		Map<String, Integer> byFramework = new java.util.TreeMap<>();
		records.stream()
			.filter(r -> r.framework() != null && r.testMethodCount() != null)
			.forEach(r -> byFramework.merge(r.framework(), r.testMethodCount(), Integer::sum));
		List<String> untested = findings.findBySnapshotIdAndRunId(snapshotId, runId)
			.stream()
			.filter(f -> f.getCategory() == AnalysisCategory.TESTS && "no-test-found".equals(f.getRuleId()))
			.map(f -> String.valueOf(f.getEvidence() == null ? f.getFilePath() : f.getEvidence().getOrDefault("class", f.getFilePath())))
			.sorted()
			.toList();
		return new TestsReportDTO(records.isEmpty() ? null : records.size(),
				counted ? records.stream().mapToInt(r -> r.testMethodCount() == null ? 0 : r.testMethodCount()).sum() : null,
				counted ? byFramework : null, records, untested);
	}

	/** Coverage from a real report, or NOT_ANALYZED with the reason: never an estimate, never 0%. */
	public CoverageReportDTO coverage(UUID repositoryId, UUID snapshotId) {
		Resolved resolved = resolve(repositoryId, snapshotId, null);
		List<CoverageRecordDTO> records = coverageRecords.findBySnapshotIdAndRunId(snapshotId, resolved.run().getId())
			.stream()
			.sorted(Comparator.comparing(com.repointelligence.analysis.domain.CoverageRecord::getReportPath)
				.thenComparing(c -> c.getScope().ordinal())
				.thenComparing(com.repointelligence.analysis.domain.CoverageRecord::getName))
			.map(c -> new CoverageRecordDTO(c.getReportPath(), c.getScope(), c.getName(), c.getLineCovered(), c.getLineMissed(),
					c.getBranchCovered(), c.getBranchMissed()))
			.toList();
		if (records.isEmpty()) {
			return new CoverageReportDTO(ExecutionStatus.NOT_ANALYZED,
					"Coverage unavailable: no JaCoCo report found in the repository. Tests are not executed in MVP 1.", List.of());
		}
		return new CoverageReportDTO(ExecutionStatus.COMPLETED,
				"Coverage from a report committed to the repository; it may be stale relative to this commit.", records);
	}

	// ── resolution ───────────────────────────────────────────────────────────────────────────────

	private record Resolved(RepositorySnapshot snapshot, AnalysisRun run) {
	}

	/** A snapshot of the repository and the run whose results describe it. */
	public record ReportScope(RepositorySnapshot snapshot, AnalysisRun run) {
	}

	/**
	 * The same resolution every report endpoint uses: 404 unless the snapshot belongs to the repository;
	 * the latest completed run unless {@code runId} names another run of that snapshot.
	 */
	public ReportScope scope(UUID repositoryId, UUID snapshotId, UUID runId) {
		Resolved resolved = resolve(repositoryId, snapshotId, runId);
		return new ReportScope(resolved.snapshot(), resolved.run());
	}

	private Resolved resolve(UUID repositoryId, UUID snapshotId, UUID runId) {
		RepositorySnapshot snapshot = snapshots.findByIdAndRepositoryId(snapshotId, repositoryId)
			.orElseThrow(() -> new ResourceNotFoundException("Snapshot not found for this repository."));
		AnalysisRun run;
		if (runId != null) {
			run = runs.findById(runId)
				.filter(r -> r.getSnapshot().getId().equals(snapshotId))
				.orElseThrow(() -> new ResourceNotFoundException("Analysis run not found for this snapshot."));
		}
		else {
			run = runs.findTopBySnapshotIdAndStatusInOrderByRequestedAtDesc(snapshotId, COMPLETED)
				.orElseThrow(() -> new ResourceNotFoundException(
						"This snapshot has no completed analysis run yet."));
		}
		return new Resolved(snapshot, run);
	}

	// ── structure ────────────────────────────────────────────────────────────────────────────────

	private StructureDTO structure(UUID snapshotId, UUID runId, List<AnalyzerExecution> runExecutions,
			List<AnalysisFinding> structureFindings) {
		List<ModuleDTO> moduleDtos = modules.findBySnapshotIdAndRunId(snapshotId, runId)
			.stream()
			.sorted(Comparator.comparing(ModuleRecord::getPath))
			.map(m -> new ModuleDTO(m.getModuleName(), m.getPath(), m.getBuildFilePath(), m.getEcosystem(),
					m.getDetectedResponsibility(), m.getEvidence()))
			.toList();
		List<String> entryPoints = structureFindings.stream()
			.filter(f -> ENTRY_POINT_RULE.equals(f.getRuleId()) && f.getFilePath() != null)
			.map(AnalysisFinding::getFilePath)
			.distinct()
			.sorted()
			.toList();
		Map<String, Object> summary = structureFindings.stream()
			.filter(f -> STRUCTURE_SUMMARY_RULE.equals(f.getRuleId()))
			.map(AnalysisFinding::getEvidence)
			.filter(java.util.Objects::nonNull)
			.findFirst()
			.orElse(null);
		return new StructureDTO(moduleDtos, entryPoints, summary);
	}

	// ── sections ─────────────────────────────────────────────────────────────────────────────────

	private List<ReportSectionDTO> sections(UUID snapshotId, UUID runId, List<AnalyzerExecution> runExecutions,
			List<AnalyzerCapability> matrix) {
		Map<AnalysisCategory, Map<Severity, Long>> counts = new EnumMap<>(AnalysisCategory.class);
		for (SeverityCount row : findings.countByCategoryAndSeverity(snapshotId, runId)) {
			counts.computeIfAbsent(row.getCategory(), c -> new EnumMap<>(Severity.class))
				.put(row.getSeverity(), row.getTotal());
		}
		Map<String, CapabilityLevel> levels = capabilityLevels(matrix);
		List<ReportSectionDTO> sections = new ArrayList<>();
		for (AnalysisCategory category : AnalysisCategory.values()) {
			List<AnalyzerExecution> inCategory = runExecutions.stream().filter(e -> e.getCategory() == category).toList();
			ExecutionStatus status = SectionAggregator
				.aggregate(inCategory.stream().map(AnalyzerExecution::getStatus).toList());
			Map<Severity, Long> severityCounts = SectionAggregator.counts(status,
					counts.getOrDefault(category, Map.of()));
			List<AnalyzerSummaryDTO> analyzers = inCategory.stream()
				.map(e -> new AnalyzerSummaryDTO(e.getAnalyzerId(), e.getAnalyzerVersion(), e.getStatus(),
						levels.get(e.getAnalyzerId()), e.getConfigSummary()))
				.toList();
			sections.add(new ReportSectionDTO(category, status, analyzers, severityCounts,
					SectionAggregator.summary(category, status, severityCounts)));
		}
		return sections;
	}

	/** Capability level per analyzer: from the matrix when present, else from the registered descriptor. */
	private Map<String, CapabilityLevel> capabilityLevels(List<AnalyzerCapability> matrix) {
		Map<String, CapabilityLevel> levels = new HashMap<>();
		registry.analyzers().forEach(a -> put(levels, a.descriptor()));
		registry.detectors().forEach(d -> put(levels, d.descriptor()));
		for (AnalyzerCapability row : matrix) {
			if (row.getAnalyzerId() != null && row.getCapabilityLevel() != null) {
				levels.put(row.getAnalyzerId(), row.getCapabilityLevel());
			}
		}
		return levels;
	}

	private static void put(Map<String, CapabilityLevel> levels, AnalyzerDescriptor descriptor) {
		levels.putIfAbsent(descriptor.id(), descriptor.capabilityLevel());
	}

	// ── limitations ──────────────────────────────────────────────────────────────────────────────

	private static List<LimitationDTO> limitations(List<AnalyzerExecution> runExecutions,
			List<AnalyzerCapability> matrix) {
		List<LimitationDTO> result = new ArrayList<>();
		for (AnalyzerExecution execution : runExecutions) {
			if (LIMITING.contains(execution.getStatus())) {
				result.add(new LimitationDTO(execution.getAnalyzerId(), execution.getCategory(), execution.getStatus(),
						execution.getErrorSummary() != null ? execution.getErrorSummary()
								: "Ended as " + execution.getStatus() + "."));
			}
			if (execution.getLimitations() != null) {
				for (String limitation : execution.getLimitations()) {
					result.add(new LimitationDTO(execution.getAnalyzerId(), execution.getCategory(),
							execution.getStatus(), limitation));
				}
			}
		}
		for (AnalyzerCapability row : matrix) {
			if (row.getAnalyzerId() == null && row.getLimitationReason() != null) {
				result.add(new LimitationDTO(null, row.getCategory(), row.getExecutionStatus(),
						row.getTechnology() + ": " + row.getLimitationReason()));
			}
		}
		return result;
	}

	// ── mapping ──────────────────────────────────────────────────────────────────────────────────

	private static TechnologyDTO toTechnology(TechnologyFinding t) {
		return new TechnologyDTO(t.getTechnology(), t.getKind(), t.getVersion(), t.getConfidence(),
				t.getSharePercent(), t.getDetectorId(), t.getEvidence());
	}

	private static CapabilityMatrixRowDTO toMatrixRow(AnalyzerCapability c) {
		return new CapabilityMatrixRowDTO(c.getTechnology(), c.getCategory(), c.getAnalyzerId(),
				c.getAnalyzerVersion(), c.getCapabilityLevel(), c.getExecutionStatus(), c.getLimitationReason());
	}

	private static FindingDTO toFinding(AnalysisFinding f, TrackedRepository repository, String sha) {
		return new FindingDTO(f.getId(), f.getSeverity(), f.getConfidence(), f.getCategory(), f.getTitle(),
				f.getDescription(), f.getFilePath(), f.getStartLine(), f.getEndLine(), f.getModulePath(), f.getRuleId(),
				f.getAnalyzerId(), f.getAnalysisMethod(), f.getEvidence(),
				SourceUrls.blobUrl(repository.getOwner(), repository.getName(), sha, f.getFilePath(), f.getStartLine(),
						f.getEndLine()),
				f.getSuggestedNextStep(), f.getFingerprint());
	}

	private static DependencyDTO toDependency(DependencyRecord d) {
		return new DependencyDTO(d.getId(), d.getAnalyzerId(), d.getEcosystem(), d.getManifestPath(),
				d.getModulePath(), d.getNamespace(), d.getName(), d.getDeclaredVersion(), d.getResolvedVersion(),
				d.getVersionSource(), d.getScope(), d.isDirect(), d.isManagementEntry(), d.getVulnerabilityStatus(),
				d.getVulnerabilityIds(), d.getNote());
	}

}
