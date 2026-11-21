package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.PatternCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatternCandidateRepository extends JpaRepository<PatternCandidate, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<PatternCandidate> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
