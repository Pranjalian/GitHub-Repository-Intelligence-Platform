package com.repointelligence.repository.application;

import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.repository.api.PageResponse;
import com.repointelligence.repository.api.RepositoryDTO;
import com.repointelligence.repository.api.RepositoryMapper;
import com.repointelligence.repository.api.SnapshotDTO;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
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

	public RepositoryQueryService(TrackedRepositoryRepository repositories, RepositorySnapshotRepository snapshots,
			AnalysisRunRepository runs, RepositoryMapper mapper) {
		this.repositories = repositories;
		this.snapshots = snapshots;
		this.runs = runs;
		this.mapper = mapper;
	}

	public PageResponse<RepositoryDTO> list(int page, int size) {
		var result = repositories.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
		return PageResponse.from(result, this::toRepositoryDto);
	}

	public RepositoryDTO get(UUID repositoryId) {
		return toRepositoryDto(repositories.findById(repositoryId).orElseThrow(() -> notFound()));
	}

	public PageResponse<SnapshotDTO> snapshots(UUID repositoryId, int page, int size) {
		if (!repositories.existsById(repositoryId)) {
			throw notFound();
		}
		var result = snapshots.findByRepositoryIdOrderByCreatedAtDesc(repositoryId, PageRequest.of(page, size));
		return PageResponse.from(result, this::toSnapshotDto);
	}

	private RepositoryDTO toRepositoryDto(TrackedRepository repository) {
		SnapshotDTO latest = snapshots.findFirstByRepositoryIdOrderByCreatedAtDesc(repository.getId())
			.map(this::toSnapshotDto)
			.orElse(null);
		return mapper.toRepository(repository, latest);
	}

	private SnapshotDTO toSnapshotDto(RepositorySnapshot snapshot) {
		return mapper.toSnapshot(snapshot,
				runs.findTopBySnapshotIdOrderByRequestedAtDesc(snapshot.getId()).orElse(null));
	}

	private static ResourceNotFoundException notFound() {
		return new ResourceNotFoundException("Repository not found.");
	}

}
