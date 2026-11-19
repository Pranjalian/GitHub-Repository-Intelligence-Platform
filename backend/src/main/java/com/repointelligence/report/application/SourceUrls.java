package com.repointelligence.report.application;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.web.util.UriUtils;

/** Builds permalinks to a file at an exact commit on GitHub. */
public final class SourceUrls {

	private SourceUrls() {
	}

	/**
	 * {@code https://github.com/{owner}/{repo}/blob/{sha}/{path}#L{start}-L{end}}. Null when there is no
	 * file path; the line fragment is omitted without a start line and collapsed when start equals end.
	 */
	public static String blobUrl(String owner, String repo, String sha, String path, Integer startLine,
			Integer endLine) {
		if (path == null || path.isBlank()) {
			return null;
		}
		String encodedPath = Arrays.stream(path.replace('\\', '/').split("/"))
			.map(segment -> UriUtils.encodePathSegment(segment, java.nio.charset.StandardCharsets.UTF_8))
			.collect(Collectors.joining("/"));
		StringBuilder url = new StringBuilder("https://github.com/").append(owner)
			.append('/')
			.append(repo)
			.append("/blob/")
			.append(sha)
			.append('/')
			.append(encodedPath);
		if (startLine != null) {
			url.append("#L").append(startLine);
			if (endLine != null && endLine > startLine) {
				url.append("-L").append(endLine);
			}
		}
		return url.toString();
	}

}
