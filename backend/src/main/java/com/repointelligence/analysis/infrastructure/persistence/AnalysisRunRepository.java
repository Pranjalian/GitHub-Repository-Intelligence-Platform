package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.common.model.RunStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

}
