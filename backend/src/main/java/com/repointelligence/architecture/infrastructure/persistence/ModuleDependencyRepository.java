package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.ModuleDependency;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleDependencyRepository extends JpaRepository<ModuleDependency, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<ModuleDependency> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
