package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.ApiEndpoint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiEndpointRepository extends JpaRepository<ApiEndpoint, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<ApiEndpoint> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
