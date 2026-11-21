package com.repointelligence.architecture.infrastructure.persistence;

import com.repointelligence.architecture.domain.CodeSymbol;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeSymbolRepository extends JpaRepository<CodeSymbol, UUID> {

	/** Rows of one run; always scoped by snapshot as well. */
	List<CodeSymbol> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	long countBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
