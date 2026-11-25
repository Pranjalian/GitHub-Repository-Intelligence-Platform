package com.repointelligence.repository.api;

import java.time.Instant;
import java.util.UUID;

public record RepositoryDTO(UUID id, String host, String owner, String name, String canonicalUrl,
		String defaultBranch, String visibility, Instant createdAt, Instant updatedAt, SnapshotDTO latestSnapshot,
		RepositorySummaryDTO summary) {
}
