package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalyzerCapability;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalyzerCapabilityRepository extends JpaRepository<AnalyzerCapability, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<AnalyzerCapability> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	/** The matrix is rewritten when a run finishes (placeholders become real statuses). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from AnalyzerCapability c where c.run.id = :runId")
	void deleteByRunId(@Param("runId") UUID runId);

}
