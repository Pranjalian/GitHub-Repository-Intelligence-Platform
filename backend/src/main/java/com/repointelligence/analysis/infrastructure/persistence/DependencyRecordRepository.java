package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.DependencyRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyRecordRepository extends JpaRepository<DependencyRecord, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<DependencyRecord> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
