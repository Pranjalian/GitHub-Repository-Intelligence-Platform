package com.repointelligence.repository.api;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import org.springframework.stereotype.Component;

/** Maps entities to API DTOs. Entities are never exposed directly. */
@Component
public class RepositoryMapper {

	public RunSummaryDTO toRunSummary(AnalysisRun run) {
		if (run == null) {
			return null;
		}
		return new RunSummaryDTO(run.getId(), run.getStatus(), run.isForceRescan(), run.getRequestedAt(),
				run.getStartedAt(), run.getCompletedAt(), run.getFailureReason());
	}

	public SnapshotDTO toSnapshot(RepositorySnapshot snapshot, AnalysisRun latestRun) {
		if (snapshot == null) {
			return null;
		}
		return new SnapshotDTO(snapshot.getId(), snapshot.getCommitSha(), snapshot.getBranch(),
				snapshot.getIngestionStatus(), snapshot.getFileCount(), snapshot.getTotalBytes(),
				snapshot.getIngestedAt(), snapshot.getCreatedAt(), toRunSummary(latestRun));
	}

	public RepositoryDTO toRepository(TrackedRepository repository, SnapshotDTO latestSnapshot) {
		return new RepositoryDTO(repository.getId(), repository.getHost(), repository.getOwner(),
				repository.getName(), repository.getCanonicalUrl(), repository.getDefaultBranch(),
				repository.getVisibility(), repository.getCreatedAt(), repository.getUpdatedAt(), latestSnapshot);
	}

}
