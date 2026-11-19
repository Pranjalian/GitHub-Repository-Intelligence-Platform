package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalyzerCapability;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyzerCapabilityRepository extends JpaRepository<AnalyzerCapability, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<AnalyzerCapability> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
