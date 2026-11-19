package com.repointelligence.repository.infrastructure.github;

/** Repository metadata as reported by GitHub. {@code sizeKb} is approximate. */
public record GitHubRepositoryInfo(long id, String owner, String name, String defaultBranch, String visibility,
		long sizeKb, boolean archived, boolean disabled) {
}
