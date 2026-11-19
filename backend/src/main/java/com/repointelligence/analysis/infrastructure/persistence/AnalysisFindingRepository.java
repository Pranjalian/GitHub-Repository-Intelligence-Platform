package com.repointelligence.analysis.infrastructure.persistence;

import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.Severity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisFindingRepository extends JpaRepository<AnalysisFinding, UUID> {

	List<AnalysisFinding> findBySnapshotIdAndRunId(UUID snapshotId, UUID runId);

	Page<AnalysisFinding> findBySnapshotIdAndRunIdAndCategory(UUID snapshotId, UUID runId, AnalysisCategory category,
			Pageable pageable);

	Page<AnalysisFinding> findBySnapshotIdAndRunIdAndSeverity(UUID snapshotId, UUID runId, Severity severity,
			Pageable pageable);

	/** Finding counts per category and severity for one run, for the report sections. */
	@Query("""
			select f.category as category, f.severity as severity, count(f) as total
			from AnalysisFinding f
			where f.snapshot.id = :snapshotId and f.run.id = :runId
			group by f.category, f.severity
			""")
	List<SeverityCount> countByCategoryAndSeverity(@Param("snapshotId") UUID snapshotId, @Param("runId") UUID runId);

	interface SeverityCount {

		AnalysisCategory getCategory();

		Severity getSeverity();

		long getTotal();

	}

}
