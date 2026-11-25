package com.repointelligence.analysis.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import java.time.Instant;

/**
 * One detector or analyzer of a run. {@code reason} says why it did not produce a result (skipped, not
 * configured, unsupported, unavailable): the error summary when there is one, otherwise the first
 * limitation the analyzer reported; null when there is nothing to say.
 */
public record ExecutionDTO(String analyzerId, AnalysisCategory category, ExecutionStatus status, Instant startedAt,
		Instant completedAt, Long durationMs, String errorSummary, String reason) {
}
