package com.repointelligence.analysis.capability;

import com.repointelligence.common.model.ExecutionStatus;

/** How an analyzer actually ended, used to fill in the matrix after the run. */
public record ExecutionOutcome(ExecutionStatus status, String reason) {
}
