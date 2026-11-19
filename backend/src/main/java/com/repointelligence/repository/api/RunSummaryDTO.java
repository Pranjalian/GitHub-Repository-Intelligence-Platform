package com.repointelligence.repository.api;

import com.repointelligence.common.model.RunStatus;
import java.time.Instant;
import java.util.UUID;

public record RunSummaryDTO(UUID id, RunStatus status, boolean forceRescan, Instant requestedAt, Instant startedAt,
		Instant completedAt, String failureReason) {
}
