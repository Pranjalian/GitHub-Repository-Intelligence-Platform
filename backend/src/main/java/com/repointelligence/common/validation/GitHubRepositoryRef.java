package com.repointelligence.common.validation;

/**
 * A validated reference to a public GitHub repository. The backend builds every outbound URL from this
 * ref and never fetches the user-supplied URL (primary SSRF control).
 */
public record GitHubRepositoryRef(String owner, String name, String requestedBranch) {

	public String canonicalUrl() {
		return "https://github.com/" + owner + "/" + name;
	}

}
