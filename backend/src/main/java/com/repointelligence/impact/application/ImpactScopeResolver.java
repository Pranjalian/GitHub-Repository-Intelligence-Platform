package com.repointelligence.impact.application;

import com.repointelligence.report.application.ReportService;
import com.repointelligence.report.application.ReportService.ReportScope;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The same resolution every report endpoint uses: 404 unless the snapshot belongs to the repository and has a completed run. */
@Service
public class ImpactScopeResolver {

	private final ReportService reports;

	public ImpactScopeResolver(ReportService reports) {
		this.reports = reports;
	}

	@Transactional(readOnly = true)
	public ImpactScope resolve(UUID repositoryId, UUID snapshotId) {
		ReportScope scope = reports.scope(repositoryId, snapshotId, null);
		var repository = scope.snapshot().getRepository();
		return new ImpactScope(repositoryId, snapshotId, scope.run().getId(), repository.getOwner(), repository.getName(),
				scope.snapshot().getCommitSha(), repository.getVisibility());
	}

}
