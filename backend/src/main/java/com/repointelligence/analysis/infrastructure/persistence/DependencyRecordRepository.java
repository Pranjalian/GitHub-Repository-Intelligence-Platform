package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.DependencyRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DependencyRecordRepository extends JpaRepository<DependencyRecord, UUID>, JpaSpecificationExecutor<DependencyRecord> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<DependencyRecord> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	/** Rows of one run; the same set the dependencies endpoint pages over. */
	long countByRunId(UUID runId);

	@org.springframework.data.jpa.repository.Query("select d.run.id as runId, count(d) as total from DependencyRecord d where d.run.id in :runIds group by d.run.id")
	List<RunTotal> countForRuns(@org.springframework.data.repository.query.Param("runIds") java.util.Collection<UUID> runIds);

	interface RunTotal {

		UUID getRunId();

		long getTotal();

	}

}
