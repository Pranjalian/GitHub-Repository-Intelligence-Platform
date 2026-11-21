package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.DependencyEdge;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<DependencyEdge> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
