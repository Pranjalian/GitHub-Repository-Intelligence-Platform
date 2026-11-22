package com.repointelligence.ai.infrastructure.persistence;

import com.repointelligence.ai.domain.AIUsageRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AIUsageRecordRepository extends JpaRepository<AIUsageRecord, UUID>, JpaSpecificationExecutor<AIUsageRecord> {

	/** Provider calls (not cache hits) counted against the call budget of one analysis run. */
	long countByRunIdAndCacheHitFalse(UUID runId);

	/** Provider calls of one feature for one snapshot (feature budgets such as Q&A). */
	long countBySnapshotIdAndFeatureAndCacheHitFalse(UUID snapshotId, String feature);

}
