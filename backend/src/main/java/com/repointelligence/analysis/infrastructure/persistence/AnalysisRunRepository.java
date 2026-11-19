package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.common.model.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

	/** Latest run of a snapshot whose status is in the given set (e.g. the completed statuses). */
	Optional<AnalysisRun> findTopBySnapshotIdAndStatusInOrderByRequestedAtDesc(UUID snapshotId,
			Collection<RunStatus> statuses);

	List<AnalysisRun> findBySnapshotIdAndStatusIn(UUID snapshotId, Collection<RunStatus> statuses);

	List<AnalysisRun> findBySnapshotIdOrderByRequestedAtDesc(UUID snapshotId);

	Optional<AnalysisRun> findTopBySnapshotIdOrderByRequestedAtDesc(UUID snapshotId);

	/** Startup recovery: runs left in an in-flight state by a previous process. */
	List<AnalysisRun> findByStatusIn(Collection<RunStatus> statuses);

	/** Queued + running job cap. */
	long countByStatusIn(Collection<RunStatus> statuses);

	/** Snapshots that currently have a run in one of the given states (their workspaces are in use). */
	@Query("select distinct r.snapshot.id from AnalysisRun r where r.status in :statuses")
	Set<UUID> findSnapshotIdsByStatusIn(@Param("statuses") Collection<RunStatus> statuses);

	/** Atomically takes a queued run for execution; 0 means it was cancelled or taken meanwhile. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AnalysisRun r set r.status = com.repointelligence.common.model.RunStatus.INGESTING, r.startedAt = :now "
			+ "where r.id = :runId and r.status = com.repointelligence.common.model.RunStatus.QUEUED")
	int claimQueued(@Param("runId") UUID runId, @Param("now") Instant now);

	/** Cancels a run that has not started; 0 means it already started (use its cancellation token). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AnalysisRun r set r.status = com.repointelligence.common.model.RunStatus.CANCELLED, r.completedAt = :now "
			+ "where r.id = :runId and r.status = com.repointelligence.common.model.RunStatus.QUEUED")
	int cancelQueued(@Param("runId") UUID runId, @Param("now") Instant now);

	/** Startup recovery: runs left in flight by a previous process can never finish. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AnalysisRun r set r.status = com.repointelligence.common.model.RunStatus.FAILED, "
			+ "r.failureReason = :reason, r.completedAt = :now where r.status in :statuses")
	int failInFlight(@Param("statuses") Collection<RunStatus> statuses, @Param("reason") String reason,
			@Param("now") Instant now);

}
