package com.repointelligence.analysis.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import java.time.Instant;

/**
 * One detector or analyzer of a run. {@code reason} is the error summary when there is one, otherwise the
 * first limitation the analyzer reported: why it produced no result (skipped, not configured, unsupported,
 * unavailable) or what its result cannot be trusted for (completed with a stated limitation). Null when
 * there is nothing to say.
 */
public record ExecutionDTO(String analyzerId, AnalysisCategory category, ExecutionStatus status, Instant startedAt,
		Instant completedAt, Long durationMs, String errorSummary, String reason) {
}
