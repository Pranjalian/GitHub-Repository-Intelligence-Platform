package com.repointelligence.rag.retrieval;

import com.repointelligence.report.application.SourceUrls;
import java.util.UUID;

/**
 * One snapshot of one repository and the analysis run whose results describe it, as plain values. Every
 * search, retrieval and Q&A query is bound to exactly one scope.
 */
public record RagScope(UUID repositoryId, UUID snapshotId, UUID runId, String owner, String repo, String commitSha,
		String visibility) {

	public String url(String path, Integer start, Integer end) {
		return SourceUrls.blobUrl(owner, repo, commitSha, path, start, end);
	}

	public boolean isPublic() {
		return "public".equalsIgnoreCase(visibility);
	}

}
