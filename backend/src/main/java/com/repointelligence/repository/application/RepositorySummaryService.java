package com.repointelligence.repository.application;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.api.RepositorySummaryDTO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the dashboard summaries for a whole page of repositories with a fixed number of queries
 * (latest completed runs, technologies, executions, finding counts, dependency counts, index states),
 * so the cost does not grow with the page size.
 */
@Service
@Transactional(readOnly = true)
public class RepositorySummaryService {

	static final Set<RunStatus> COMPLETED = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_WARNINGS);

	private static final Set<ExecutionStatus> ANALYZED = Set.of(ExecutionStatus.COMPLETED,
			ExecutionStatus.COMPLETED_WITH_WARNINGS);

	private static final List<AnalysisCategory> FINDING_CATEGORIES = List.of(AnalysisCategory.CODE_QUALITY,
			AnalysisCategory.SECURITY);

	private final AnalysisRunRepository runs;

	private final TechnologyFindingRepository technologies;

	private final AnalyzerExecutionRepository executions;

	private final AnalysisFindingRepository findings;

	private final DependencyRecordRepository dependencies;

	private final ObjectProvider<SnapshotIndexStatusProvider> indexStatus;

	public RepositorySummaryService(AnalysisRunRepository runs, TechnologyFindingRepository technologies,
			AnalyzerExecutionRepository executions, AnalysisFindingRepository findings,
			DependencyRecordRepository dependencies, ObjectProvider<SnapshotIndexStatusProvider> indexStatus) {
		this.runs = runs;
		this.technologies = technologies;
		this.executions = executions;
		this.findings = findings;
		this.dependencies = dependencies;
		this.indexStatus = indexStatus;
	}

	/** Summary per repository id; a repository without a completed run is absent from the map. */
	public Map<UUID, RepositorySummaryDTO> summarize(Collection<UUID> repositoryIds) {
		if (repositoryIds.isEmpty()) {
			return Map.of();
		}
		List<AnalysisRun> latest = runs.findLatestCompletedOfRepositories(repositoryIds, COMPLETED);
		// Ties on completed_at (same instant) are broken deterministically: highest run id wins.
		Map<UUID, AnalysisRun> byRepository = new HashMap<>();
		for (AnalysisRun run : latest) {
			byRepository.merge(run.getSnapshot().getRepository().getId(), run,
					(a, b) -> a.getId().compareTo(b.getId()) >= 0 ? a : b);
		}
		if (byRepository.isEmpty()) {
			return Map.of();
		}
		List<UUID> runIds = byRepository.values().stream().map(AnalysisRun::getId).toList();
		List<UUID> snapshotIds = byRepository.values().stream().map(r -> r.getSnapshot().getId()).toList();

		Map<UUID, List<TechnologyFinding>> techByRun = new HashMap<>();
		technologies.findByRunIdIn(runIds).forEach(t -> techByRun.computeIfAbsent(t.getRun().getId(), k -> new ArrayList<>()).add(t));
		Map<UUID, Map<AnalysisCategory, List<ExecutionStatus>>> statusByRun = new HashMap<>();
		executions.findByRunIdIn(runIds)
			.forEach(e -> statusByRun.computeIfAbsent(e.getRun().getId(), k -> new EnumMap<>(AnalysisCategory.class))
				.computeIfAbsent(e.getCategory(), k -> new ArrayList<>())
				.add(e.getStatus()));
		Map<UUID, Map<AnalysisCategory, Map<Severity, Long>>> countsByRun = new HashMap<>();
		findings.countForRuns(runIds, FINDING_CATEGORIES)
			.forEach(row -> countsByRun.computeIfAbsent(row.getRunId(), k -> new EnumMap<>(AnalysisCategory.class))
				.computeIfAbsent(row.getCategory(), k -> new EnumMap<>(Severity.class))
				.put(row.getSeverity(), row.getTotal()));
		Map<UUID, Long> dependencyCounts = new HashMap<>();
		dependencies.countForRuns(runIds).forEach(row -> dependencyCounts.put(row.getRunId(), row.getTotal()));
		SnapshotIndexStatusProvider provider = indexStatus.getIfAvailable();
		Map<UUID, String> indexStates = provider == null ? Map.of() : provider.statusesOf(snapshotIds);

		Map<UUID, RepositorySummaryDTO> result = new HashMap<>();
		byRepository.forEach((repositoryId, run) -> {
			Map<AnalysisCategory, List<ExecutionStatus>> statuses = statusByRun.getOrDefault(run.getId(), Map.of());
			List<TechnologyFinding> tech = techByRun.getOrDefault(run.getId(), List.of());

			TechnologyFinding language = PrimaryLanguage
				.choose(tech.stream().filter(t -> t.getKind() == TechnologyKind.LANGUAGE).toList(),
						TechnologyFinding::getTechnology, TechnologyFinding::getSharePercent)
				.orElse(null);
			List<String> frameworks = tech.stream()
				.filter(t -> t.getKind() == TechnologyKind.FRAMEWORK)
				.sorted(Comparator.comparing((TechnologyFinding t) -> t.getConfidence().ordinal())
					.thenComparing(TechnologyFinding::getTechnology))
				.map(TechnologyFinding::getTechnology)
				.distinct()
				.limit(2)
				.toList();

			Map<AnalysisCategory, Map<Severity, Long>> bySeverity = new LinkedHashMap<>();
			Long open = null;
			for (AnalysisCategory category : FINDING_CATEGORIES) {
				if (analyzed(statuses.get(category))) {
					Map<Severity, Long> counts = new EnumMap<>(Severity.class);
					Map<Severity, Long> found = countsByRun.getOrDefault(run.getId(), Map.of())
						.getOrDefault(category, Map.of());
					for (Severity severity : Severity.values()) {
						counts.put(severity, found.getOrDefault(severity, 0L));
					}
					bySeverity.put(category, counts);
					open = (open == null ? 0L : open) + counts.values().stream().mapToLong(Long::longValue).sum();
				}
				else {
					bySeverity.put(category, null);
				}
			}
			Long dependencyCount = analyzed(statuses.get(AnalysisCategory.DEPENDENCIES))
					? dependencyCounts.getOrDefault(run.getId(), 0L) : null;
			Long duration = run.getStartedAt() != null && run.getCompletedAt() != null
					? Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis() : null;
			result.put(repositoryId,
					new RepositorySummaryDTO(run.getSnapshot().getId(), run.getSnapshot().getCommitSha(),
							language == null ? null : language.getTechnology(),
							language == null ? null : language.getSharePercent(), frameworks, dependencyCount, bySeverity,
							open, indexStates.getOrDefault(run.getSnapshot().getId(), "NOT_INDEXED"), run.getStatus(),
							duration, run.getCompletedAt()));
		});
		return result;
	}

	private static boolean analyzed(List<ExecutionStatus> statuses) {
		return statuses != null && statuses.stream().anyMatch(ANALYZED::contains);
	}

}
