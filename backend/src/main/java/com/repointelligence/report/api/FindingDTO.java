package com.repointelligence.report.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import java.util.Map;
import java.util.UUID;

/**
 * {@code sourceUrl} points at the exact commit on GitHub, never at a moving branch. Findings with the same
 * {@code fingerprint} are the same issue reported by different analyzers; clients group them.
 */
public record FindingDTO(UUID id, Severity severity, Confidence confidence, AnalysisCategory category, String title,
		String description, String filePath, Integer startLine, Integer endLine, String module, String ruleId,
		String analyzerId, AnalysisMethod analysisMethod, Map<String, Object> evidence, String sourceUrl,
		String suggestedNextStep, String fingerprint) {
}
