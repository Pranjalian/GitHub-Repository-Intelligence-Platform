package com.repointelligence.report.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import java.util.List;
import java.util.Map;

/**
 * One report section per analysis category. {@code findingCountsBySeverity} is null (never zeros) when
 * the category was not analyzed; when it was analyzed it has an entry for every severity, so "analyzed
 * and found nothing" is distinguishable from "not analyzed".
 */
public record ReportSectionDTO(AnalysisCategory category, ExecutionStatus status, List<AnalyzerSummaryDTO> analyzers,
		Map<Severity, Long> findingCountsBySeverity, String summary) {
}
