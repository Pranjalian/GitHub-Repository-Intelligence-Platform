package com.repointelligence.report.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;

/** Something the report could not do, and why. */
public record LimitationDTO(String analyzerId, AnalysisCategory category, ExecutionStatus status, String reason) {
}
