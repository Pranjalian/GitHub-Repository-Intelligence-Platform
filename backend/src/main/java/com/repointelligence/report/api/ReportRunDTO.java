package com.repointelligence.report.api;

import com.repointelligence.common.model.RunStatus;
import java.time.Instant;
import java.util.UUID;

public record ReportRunDTO(UUID id, RunStatus status, Instant completedAt) {
}
