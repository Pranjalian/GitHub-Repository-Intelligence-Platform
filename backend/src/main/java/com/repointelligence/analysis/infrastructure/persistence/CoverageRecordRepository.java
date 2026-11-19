package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.CoverageRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoverageRecordRepository extends JpaRepository<CoverageRecord, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<CoverageRecord> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
