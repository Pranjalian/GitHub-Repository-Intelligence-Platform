package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.TechnologyFinding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TechnologyFindingRepository extends JpaRepository<TechnologyFinding, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<TechnologyFinding> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	/** Rows of several runs of already-scoped snapshots (dashboard rows). */
	List<TechnologyFinding> findByRunIdIn(java.util.Collection<UUID> runIds);

}
