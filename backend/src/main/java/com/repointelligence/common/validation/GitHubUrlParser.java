package com.repointelligence.common.validation;

import com.repointelligence.common.exception.InvalidRepositoryUrlException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Strict parser for GitHub repository URLs. Accepts {@code https://github.com/{owner}/{repo}} with an
 * optional {@code .git}, trailing slash, {@code www.}, no scheme, or a {@code /tree/{branch}} suffix.
 */
@Component
public class GitHubUrlParser {

	private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");

	private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

	private static final Pattern BRANCH_SEGMENT = Pattern.compile("^[A-Za-z0-9._@+-]{1,100}$");

	private static final int MAX_LENGTH = 500;

	public GitHubRepositoryRef parse(String input) {
		if (input == null || input.isBlank()) {
			throw invalid("Repository URL must not be empty.");
		}
		String url = input.strip();
		if (url.length() > MAX_LENGTH || url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
			throw invalid("Repository URL contains invalid characters.");
		}
		if (!url.contains("://")) {
			url = "https://" + url;
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			throw invalid("Repository URL is malformed.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http")) {
			throw invalid("Only http(s) GitHub URLs are supported.");
		}
		if (uri.getRawUserInfo() != null) {
			throw invalid("Repository URL must not contain user information.");
		}
		if (uri.getPort() != -1) {
			throw invalid("Repository URL must not specify a port.");
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (!host.equals("github.com") && !host.equals("www.github.com")) {
			throw invalid("Only github.com repositories are supported.");
		}
		if (uri.getRawQuery() != null) {
			throw invalid("Repository URL must not contain a query string.");
		}

		String path = uri.getPath() == null ? "" : uri.getPath();
		String[] segments = path.replaceAll("^/+|/+$", "").split("/", -1);
		if (segments.length < 2) {
			throw invalid("Repository URL must look like https://github.com/{owner}/{repo}.");
		}
		String owner = segments[0];
		String repo = segments[1].endsWith(".git") ? segments[1].substring(0, segments[1].length() - 4) : segments[1];
		if (!OWNER.matcher(owner).matches()) {
			throw invalid("Invalid GitHub owner name.");
		}
		if (!REPO.matcher(repo).matches() || repo.equals(".") || repo.equals("..")) {
			throw invalid("Invalid GitHub repository name.");
		}

		String branch = null;
		if (segments.length > 2) {
			if (!segments[2].equals("tree") || segments.length < 4) {
				throw invalid("Only repository root and /tree/{branch} URLs are supported.");
			}
			branch = parseBranch(segments, 3);
		}
		return new GitHubRepositoryRef(owner, repo, branch);
	}

	/** The branch is recorded for the API lookup only; it is never used as a filesystem path. */
	private String parseBranch(String[] segments, int from) {
		StringBuilder branch = new StringBuilder();
		for (int i = from; i < segments.length; i++) {
			String segment = segments[i];
			if (segment.equals(".") || segment.equals("..") || !BRANCH_SEGMENT.matcher(segment).matches()) {
				throw invalid("Invalid branch name.");
			}
			if (i > from) {
				branch.append('/');
			}
			branch.append(segment);
		}
		return branch.toString();
	}

	private InvalidRepositoryUrlException invalid(String message) {
		return new InvalidRepositoryUrlException(message);
	}

}
