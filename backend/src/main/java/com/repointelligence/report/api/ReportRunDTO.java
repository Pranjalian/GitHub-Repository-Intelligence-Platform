package com.repointelligence.report.api;

import com.repointelligence.common.model.RunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The run a report describes. {@code durationMs} is the wall-clock of the run (null when its start was
 * not recorded); {@code analyzerSetHash} identifies the set of analyzers and rules that produced it.
 */
public record ReportRunDTO(UUID id, RunStatus status, Instant completedAt, Instant startedAt, Long durationMs,
		String analyzerSetHash) {
}
