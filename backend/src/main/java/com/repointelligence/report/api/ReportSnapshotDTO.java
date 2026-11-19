package com.repointelligence.report.api;

import java.time.Instant;
import java.util.UUID;

/** The commit a report describes. Every repository-derived number in a report belongs to this one commit. */
public record ReportSnapshotDTO(UUID id, String commitSha, String branch, Instant analyzedAt) {
}
