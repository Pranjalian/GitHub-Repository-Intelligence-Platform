package com.repointelligence.repository.api;

import com.repointelligence.common.model.IngestionStatus;
import java.time.Instant;
import java.util.UUID;

/** A repository at one commit, with the status of its most recent analysis run (if any). */
public record SnapshotDTO(UUID id, String commitSha, String branch, IngestionStatus ingestionStatus,
		Integer fileCount, Long totalBytes, Instant ingestedAt, Instant createdAt, RunSummaryDTO latestRun) {
}
