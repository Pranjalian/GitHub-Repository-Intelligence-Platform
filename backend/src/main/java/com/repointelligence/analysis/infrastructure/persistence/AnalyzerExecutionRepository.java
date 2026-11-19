package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalyzerExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyzerExecutionRepository extends JpaRepository<AnalyzerExecution, UUID> {

	List<AnalyzerExecution> findByRunId(UUID runId);

	List<AnalyzerExecution> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

}
