package com.repointelligence.repository.application;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.repository.api.PageResponse;
import com.repointelligence.repository.api.RepositoryDTO;
import com.repointelligence.repository.api.RepositoryMapper;
import com.repointelligence.repository.api.RepositoryStatsDTO;
import com.repointelligence.repository.api.RepositorySummaryDTO;
import com.repointelligence.repository.api.SnapshotDTO;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the repository API. Sorting is fixed server-side; clients cannot choose sort columns. */
@Service
@Transactional(readOnly = true)
public class RepositoryQueryService {

	private final TrackedRepositoryRepository repositories;

	private final RepositorySnapshotRepository snapshots;

	private final AnalysisRunRepository runs;

	private final RepositoryMapper mapper;

	private final RepositorySummaryService summaries;

	public RepositoryQueryService(TrackedRepositoryRepository repositories, RepositorySnapshotRepository snapshots,
			AnalysisRunRepository runs, RepositoryMapper mapper, RepositorySummaryService summaries) {
		this.repositories = repositories;
		this.snapshots = snapshots;
		this.runs = runs;
		this.mapper = mapper;
		this.summaries = summaries;
	}

	/** One page of repositories; latest snapshots, runs and summaries are fetched in batches, not per row. */
	public PageResponse<RepositoryDTO> list(int page, int size) {
		var result = repositories.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
		List<UUID> ids = result.getContent().stream().map(TrackedRepository::getId).toList();
		Map<UUID, RepositorySnapshot> latestSnapshots = new HashMap<>();
		if (!ids.isEmpty()) {
			// Two snapshots of one repository can share a created_at only in theory; the newest id wins then.
			snapshots.findLatestOfRepositories(ids)
				.forEach(s -> latestSnapshots.merge(s.getRepository().getId(), s,
						(a, b) -> a.getId().compareTo(b.getId()) >= 0 ? a : b));
		}
		Map<UUID, AnalysisRun> latestRuns = new HashMap<>();
		if (!latestSnapshots.isEmpty()) {
			runs.findLatestOfSnapshots(latestSnapshots.values().stream().map(RepositorySnapshot::getId).toList())
				.forEach(r -> latestRuns.merge(r.getSnapshot().getId(), r,
						(a, b) -> a.getId().compareTo(b.getId()) >= 0 ? a : b));
		}
		Map<UUID, RepositorySummaryDTO> summaryById = summaries.summarize(ids);
		return PageResponse.from(result, repository -> {
			RepositorySnapshot latest = latestSnapshots.get(repository.getId());
			SnapshotDTO snapshotDto = latest == null ? null
					: mapper.toSnapshot(latest, latestRuns.get(latest.getId()));
			return mapper.toRepository(repository, snapshotDto, summaryById.get(repository.getId()));
		});
	}

	/** The KPI tiles of the dashboard, computed over all repositories in pages of 100. */
	public RepositoryStatsDTO stats() {
		long total = repositories.count();
		long analyzed = 0;
		long indexed = 0;
		Long open = null;
		int page = 0;
		while (true) {
			var batch = repositories.findAll(PageRequest.of(page++, 100, Sort.by("id")));
			Map<UUID, RepositorySummaryDTO> summaryById = summaries
				.summarize(batch.getContent().stream().map(TrackedRepository::getId).toList());
			for (RepositorySummaryDTO summary : summaryById.values()) {
				analyzed++;
				if ("COMPLETED".equals(summary.indexStatus()) || "COMPLETED_WITH_WARNINGS".equals(summary.indexStatus())) {
					indexed++;
				}
				if (summary.openFindings() != null) {
					open = (open == null ? 0L : open) + summary.openFindings();
				}
			}
			if (!batch.hasNext()) {
				break;
			}
		}
		long active = runs.countByStatusIn(List.of(RunStatus.QUEUED, RunStatus.INGESTING, RunStatus.ANALYZING));
		return new RepositoryStatsDTO(total, analyzed, indexed, open, active);
	}

	public RepositoryDTO get(UUID repositoryId) {
		TrackedRepository repository = repositories.findById(repositoryId).orElseThrow(() -> notFound());
		return toRepositoryDto(repository, summaries.summarize(List.of(repositoryId)).get(repositoryId));
	}

	public PageResponse<SnapshotDTO> snapshots(UUID repositoryId, int page, int size) {
		if (!repositories.existsById(repositoryId)) {
			throw notFound();
		}
		var result = snapshots.findByRepositoryIdOrderByCreatedAtDesc(repositoryId, PageRequest.of(page, size));
		return PageResponse.from(result, this::toSnapshotDto);
	}

	private RepositoryDTO toRepositoryDto(TrackedRepository repository, RepositorySummaryDTO summary) {
		SnapshotDTO latest = snapshots.findFirstByRepositoryIdOrderByCreatedAtDesc(repository.getId())
			.map(this::toSnapshotDto)
			.orElse(null);
		return mapper.toRepository(repository, latest, summary);
	}

	private SnapshotDTO toSnapshotDto(RepositorySnapshot snapshot) {
		return mapper.toSnapshot(snapshot,
				runs.findTopBySnapshotIdOrderByRequestedAtDesc(snapshot.getId()).orElse(null));
	}

	private static ResourceNotFoundException notFound() {
		return new ResourceNotFoundException("Repository not found.");
	}

}
