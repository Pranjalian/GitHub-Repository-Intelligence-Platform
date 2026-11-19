package com.repointelligence.analysis.api;

import com.repointelligence.common.model.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Status of one analysis run. {@code phase} is QUEUED, INGESTING, DETECTING (technology detection),
 * ANALYZING (analyzers running) or FINISHED; {@code executions} lists each detector and analyzer with its
 * own status, so a failure in one is visible without hiding the others.
 */
public record AnalysisRunDTO(UUID runId, UUID repositoryId, UUID snapshotId, String commitSha, RunStatus status,
		String failureReason, Instant retryAfter, Instant requestedAt, Instant startedAt, Instant completedAt,
		String phase, List<ExecutionDTO> executions) {
}
