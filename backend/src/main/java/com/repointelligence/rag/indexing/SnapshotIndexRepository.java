package com.repointelligence.rag.indexing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotIndexRepository extends JpaRepository<SnapshotIndex, UUID> {

	/** Always by snapshot and repository together, like every other repository-derived read. */
	Optional<SnapshotIndex> findBySnapshotIdAndRepositoryId(UUID snapshotId, UUID repositoryId);

	List<SnapshotIndex> findByStatusIn(Collection<IndexStatus> statuses);

}
