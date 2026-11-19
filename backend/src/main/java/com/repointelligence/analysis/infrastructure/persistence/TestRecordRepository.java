package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.TestRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRecordRepository extends JpaRepository<TestRecord, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<TestRecord> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
