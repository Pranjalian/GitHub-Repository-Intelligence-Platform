package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.DependencyEdge;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.RelationshipType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<DependencyEdge> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	List<DependencyEdge> findBySnapshotIdAndRunIdAndRelationshipType(UUID snapshotId, UUID runId,
			RelationshipType relationshipType);

	interface ResolutionCount {

		EdgeResolution getResolution();

		long getTotal();

	}

	@Query("SELECT e.resolution AS resolution, COUNT(e) AS total FROM DependencyEdge e "
			+ "WHERE e.snapshot.id = :snapshotId AND e.run.id = :runId GROUP BY e.resolution")
	List<ResolutionCount> countByResolution(@Param("snapshotId") UUID snapshotId, @Param("runId") UUID runId);

}
