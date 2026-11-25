package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalyzerExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import com.repointelligence.common.model.RunStatus;

public interface AnalyzerExecutionRepository extends JpaRepository<AnalyzerExecution, UUID> {

	List<AnalyzerExecution> findByRunId(UUID runId);

	/** Executions of several runs at once (dashboard rows). */
	List<AnalyzerExecution> findByRunIdIn(Collection<UUID> runIds);

	List<AnalyzerExecution> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	Optional<AnalyzerExecution> findByRunIdAndAnalyzerId(UUID runId, String analyzerId);

	/** Startup recovery: pending executions of runs that were in flight can never finish. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AnalyzerExecution e set e.status = com.repointelligence.common.model.ExecutionStatus.FAILED, "
			+ "e.errorSummary = :reason where e.status = com.repointelligence.common.model.ExecutionStatus.NOT_ANALYZED "
			+ "and e.run.id in (select r.id from AnalysisRun r where r.status in :statuses)")
	int failPendingOfRuns(@Param("statuses") Collection<RunStatus> statuses, @Param("reason") String reason);

}
