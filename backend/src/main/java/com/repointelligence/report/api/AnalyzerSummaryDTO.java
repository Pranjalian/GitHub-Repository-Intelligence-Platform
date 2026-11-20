package com.repointelligence.report.api;

import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import java.util.Map;

/**
 * One analyzer's part in a report section. {@code capabilityLevel} is null if the analyzer is no longer
 * registered. {@code coverage} is what the analyzer reports it actually covered (files scanned, rule files, ...).
 */
public record AnalyzerSummaryDTO(String id, String version, ExecutionStatus status, CapabilityLevel capabilityLevel,
		Map<String, Object> coverage) {
}
