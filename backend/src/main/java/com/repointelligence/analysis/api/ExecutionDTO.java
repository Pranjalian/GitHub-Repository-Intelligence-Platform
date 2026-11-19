package com.repointelligence.analysis.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import java.time.Instant;

public record ExecutionDTO(String analyzerId, AnalysisCategory category, ExecutionStatus status, Instant startedAt,
		Instant completedAt, Long durationMs, String errorSummary) {
}
