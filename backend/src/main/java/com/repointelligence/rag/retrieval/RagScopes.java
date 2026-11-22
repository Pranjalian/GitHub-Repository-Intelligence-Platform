package com.repointelligence.rag.retrieval;

import com.repointelligence.report.application.ReportService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a scope exactly like the report endpoints: 404 unless the snapshot belongs to the repository and has a completed run. */
@Component
public class RagScopes {

	private final ReportService reports;

	public RagScopes(ReportService reports) {
		this.reports = reports;
	}

	@Transactional(readOnly = true)
	public RagScope resolve(UUID repositoryId, UUID snapshotId) {
		ReportService.ReportScope scope = reports.scope(repositoryId, snapshotId, null);
		var repository = scope.snapshot().getRepository();
		return new RagScope(repositoryId, snapshotId, scope.run().getId(), repository.getOwner(), repository.getName(),
				scope.snapshot().getCommitSha(), repository.getVisibility());
	}

}
