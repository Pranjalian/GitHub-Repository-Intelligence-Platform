package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.EntityRelationship;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityRelationshipRepository extends JpaRepository<EntityRelationship, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<EntityRelationship> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
