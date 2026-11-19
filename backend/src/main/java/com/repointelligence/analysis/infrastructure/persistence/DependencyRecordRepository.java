package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.DependencyRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DependencyRecordRepository extends JpaRepository<DependencyRecord, UUID>, JpaSpecificationExecutor<DependencyRecord> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<DependencyRecord> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
