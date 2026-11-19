package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.ModuleRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleRecordRepository extends JpaRepository<ModuleRecord, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<ModuleRecord> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
