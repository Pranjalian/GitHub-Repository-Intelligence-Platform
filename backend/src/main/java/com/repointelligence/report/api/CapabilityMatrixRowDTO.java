package com.repointelligence.report.api;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;

public record CapabilityMatrixRowDTO(String technology, AnalysisCategory category, String analyzerId,
		String analyzerVersion, CapabilityLevel capabilityLevel, ExecutionStatus executionStatus,
		String limitationReason) {
}
