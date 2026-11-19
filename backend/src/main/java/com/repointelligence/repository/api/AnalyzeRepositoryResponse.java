package com.repointelligence.repository.api;

import com.repointelligence.common.model.RunStatus;
import java.util.List;
import java.util.UUID;

/** {@code reused} is true when an existing run is returned instead of starting a new one. */
public record AnalyzeRepositoryResponse(UUID repositoryId, UUID snapshotId, String commitSha, String branch,
		UUID analysisRunId, RunStatus status, boolean reused, List<String> warnings) {
}
