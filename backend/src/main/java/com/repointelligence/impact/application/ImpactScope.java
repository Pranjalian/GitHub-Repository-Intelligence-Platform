package com.repointelligence.impact.application;

import java.util.UUID;

/** The snapshot and completed run an impact request is bound to, as plain values (no open persistence session needed). */
public record ImpactScope(UUID repositoryId, UUID snapshotId, UUID runId, String owner, String repo, String commitSha,
		String visibility) {

	String url(String path, Integer start, Integer end) {
		return path == null ? null : com.repointelligence.report.application.SourceUrls.blobUrl(owner, repo, commitSha, path, start, end);
	}

}
