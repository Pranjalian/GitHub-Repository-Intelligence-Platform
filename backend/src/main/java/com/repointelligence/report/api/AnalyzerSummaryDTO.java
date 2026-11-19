package com.repointelligence.report.api;

import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;

/** One analyzer's part in a report section. {@code capabilityLevel} is null if the analyzer is no longer registered. */
public record AnalyzerSummaryDTO(String id, String version, ExecutionStatus status, CapabilityLevel capabilityLevel) {
}
