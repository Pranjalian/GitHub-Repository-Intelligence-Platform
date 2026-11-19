package com.repointelligence.analysis.capability;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;

/**
 * One line of the capability matrix: for a detected technology and a category, which analyzer covers
 * it, how deeply, and what actually happened. {@code analyzerId} is null when no analyzer exists.
 */
public record CapabilityRow(String technology, AnalysisCategory category, String analyzerId, String analyzerVersion,
		CapabilityLevel capabilityLevel, ExecutionStatus executionStatus, String limitationReason) {

	public CapabilityRow withExecution(ExecutionStatus status, String reason) {
		return new CapabilityRow(technology, category, analyzerId, analyzerVersion, capabilityLevel, status,
				reason != null ? reason : limitationReason);
	}

}
