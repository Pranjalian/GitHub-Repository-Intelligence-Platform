package com.repointelligence.repository.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a dashboard row shows about a repository, taken from its most recently completed run. Honesty
 * rules: the whole summary is null when the repository has no completed run, a count is null (never 0)
 * for something that was not analyzed, and {@code findingsBySeverity} holds a null value for a category
 * that was not analyzed (keys: CODE_QUALITY and SECURITY).
 *
 * @param snapshotId the commit this summary describes (it may be older than the repository's newest snapshot)
 * @param dependencyCount rows the dependencies endpoint pages over; null when no dependency analyzer completed
 * @param openFindings total of the analyzed categories; null when neither category was analyzed
 * @param indexStatus search index state of that snapshot (NOT_INDEXED when there is no index job)
 * @param latestRunStatus status of the completed run the summary comes from
 * @param analysisDurationMs wall-clock of that run, null when its start time was not recorded
 */
public record RepositorySummaryDTO(UUID snapshotId, String commitSha, String primaryLanguage,
		BigDecimal primaryLanguageShare, List<String> frameworks, Long dependencyCount,
		Map<AnalysisCategory, Map<Severity, Long>> findingsBySeverity, Long openFindings, String indexStatus,
		RunStatus latestRunStatus, Long analysisDurationMs, Instant analyzedAt) {
}
