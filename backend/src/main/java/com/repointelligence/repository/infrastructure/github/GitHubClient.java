package com.repointelligence.repository.infrastructure.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.repointelligence.common.exception.AnalysisLimitException;
import com.repointelligence.common.exception.ApiException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.exception.UpstreamUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Small GitHub REST client: repository metadata, commit SHA resolution and tarball download.
 *
 * <p>
 * Outbound traffic is restricted to an allowlist of hosts, including every redirect hop (redirects
 * are followed manually). The token is sent only to the configured API host, never to redirect
 * targets such as codeload.github.com, and is never logged. The user-supplied URL is never
 * fetched: callers pass an already validated owner and name.
 */
@Component
public class GitHubClient {

	private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

	private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

	private static final String REPO_NOT_FOUND = "Repository not found or not public.";

	private static final String REF_NOT_FOUND = "Repository, branch or commit not found.";

	private static final int BUFFER_SIZE = 16 * 1024;

	private final GitHubProperties properties;

	private final GitHubRateLimitPolicy rateLimitPolicy;

	private final Sleeper sleeper;

	private final RestClient restClient;

	private final Set<String> allowedHosts;

	private final String apiHost;

	public GitHubClient(GitHubProperties properties, GitHubRateLimitPolicy rateLimitPolicy, Sleeper sleeper) {
		this.properties = properties;
		this.rateLimitPolicy = rateLimitPolicy;
		this.sleeper = sleeper;
		this.allowedHosts = properties.allowedHosts().stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		this.apiHost = URI.create(properties.apiBaseUrl()).getHost().toLowerCase(Locale.ROOT);
		if (!allowedHosts.contains(apiHost)) {
			throw new IllegalStateException("github.api-base-url host '" + apiHost + "' is not in github.allowed-hosts");
		}
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.version(HttpClient.Version.HTTP_1_1)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	public GitHubRepositoryInfo getRepository(String owner, String name) {
		URI uri = apiUri("repos", owner, name);
		RepositoryResponse response = execute(uri, REPO_NOT_FOUND, res -> res.bodyTo(RepositoryResponse.class));
		if (response == null) {
			throw unexpectedResponse();
		}
		String visibility = response.visibility() != null ? response.visibility()
				: (response.isPrivate() ? "private" : "public");
		return new GitHubRepositoryInfo(response.id(), response.owner() != null ? response.owner().login() : owner,
				response.name() != null ? response.name() : name, response.defaultBranch(), visibility, response.size(),
				response.archived(), response.disabled());
	}

	/** Resolves a branch, tag or SHA to the exact 40-character commit SHA. */
	public String resolveCommitSha(String owner, String name, String ref) {
		UriComponentsBuilder builder = baseBuilder().pathSegment("repos", owner, name, "commits");
		for (String segment : ref.split("/")) {
			builder.pathSegment(segment);
		}
		CommitResponse response = execute(builder.build().encode().toUri(), REF_NOT_FOUND,
				res -> res.bodyTo(CommitResponse.class));
		if (response == null || response.sha() == null || !SHA.matcher(response.sha()).matches()) {
			throw unexpectedResponse();
		}
		return response.sha();
	}

	/**
	 * Downloads the tarball of an exact commit to {@code target}, aborting as soon as more than
	 * {@code maxBytes} would be written. A partial file is deleted on any failure.
	 */
	public void downloadTarball(String owner, String name, String sha, Path target, long maxBytes) {
		if (sha == null || !SHA.matcher(sha).matches()) {
			throw new IllegalArgumentException("sha must be a full 40-character lowercase commit SHA");
		}
		URI uri = apiUri("repos", owner, name, "tarball", sha);
		try {
			Path parent = target.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		}
		catch (IOException ex) {
			throw new UpstreamUnavailableException(HttpStatus.INTERNAL_SERVER_ERROR,
					"Cannot prepare the download location.");
		}
		execute(uri, REF_NOT_FOUND, res -> {
			writeLimited(res.getHeaders(), res.getBody(), target, maxBytes);
			return Boolean.TRUE;
		});
	}

	// ---- request execution ---------------------------------------------------------------------

	@FunctionalInterface
	private interface ResponseHandler<T> {

		T handle(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException;

	}

	private record Attempt<T>(T value, boolean done, URI redirect, Duration retryDelay, ApiException ifExhausted) {

		static <T> Attempt<T> ok(T value) {
			return new Attempt<>(value, true, null, null, null);
		}

		static <T> Attempt<T> redirectTo(URI location) {
			return new Attempt<>(null, false, location, null, null);
		}

		static <T> Attempt<T> retry(Duration delay, ApiException ifExhausted) {
			return new Attempt<>(null, false, null, delay, ifExhausted);
		}

	}

	private <T> T execute(URI start, String notFoundMessage, ResponseHandler<T> onSuccess) {
		URI current = start;
		int attempt = 1;
		int hops = 0;
		int maxAttempts = properties.retry().maxAttempts();
		while (true) {
			Attempt<T> result = send(current, notFoundMessage, onSuccess);
			if (result.done()) {
				return result.value();
			}
			if (result.redirect() != null) {
				if (++hops > properties.maxRedirects()) {
					throw new UpstreamUnavailableException("GitHub redirected too many times.");
				}
				current = checkedRedirect(current, result.redirect());
				continue;
			}
			if (attempt >= maxAttempts) {
				throw result.ifExhausted();
			}
			Duration delay = result.retryDelay() != null ? result.retryDelay() : rateLimitPolicy.backoff(attempt);
			log.warn("GitHub request to {} failed (attempt {}/{}); retrying in {} ms", describe(current), attempt,
					maxAttempts, delay.toMillis());
			pause(delay);
			attempt++;
		}
	}

	private <T> Attempt<T> send(URI uri, String notFoundMessage, ResponseHandler<T> onSuccess) {
		boolean authenticated = properties.hasToken() && apiHost.equalsIgnoreCase(uri.getHost());
		log.debug("GitHub GET {}", describe(uri));
		try {
			return restClient.get()
				.uri(uri)
				.headers(headers -> addHeaders(headers, authenticated))
				.exchange((request, response) -> classify(response, uri, notFoundMessage, onSuccess));
		}
		catch (ResourceAccessException ex) {
			// Connection failure, timeout or interrupted transfer: transient.
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			return Attempt.retry(null, new UpstreamUnavailableException(
					"Cannot reach GitHub (" + cause.getClass().getSimpleName() + ")."));
		}
	}

	private <T> Attempt<T> classify(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, URI uri,
			String notFoundMessage, ResponseHandler<T> onSuccess) throws IOException {
		int status = response.getStatusCode().value();
		if (status >= 200 && status < 300) {
			return Attempt.ok(onSuccess.handle(response));
		}
		if (status >= 300 && status < 400) {
			String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
			if (location == null || location.isBlank()) {
				throw new UpstreamUnavailableException("GitHub sent a redirect without a location.");
			}
			return Attempt.redirectTo(uri.resolve(location));
		}
		GitHubRateLimitPolicy.Decision decision = rateLimitPolicy.evaluate(status, response.getHeaders(), Instant.now());
		if (decision instanceof GitHubRateLimitPolicy.Decision.FailFast failFast) {
			throw failFast.exception();
		}
		if (decision instanceof GitHubRateLimitPolicy.Decision.RetryAfter retryAfter) {
			return Attempt.retry(retryAfter.delay(), retryAfter.ifExhausted());
		}
		if (status == 404 || status == 410 || status == 422 || status == 451) {
			throw new ResourceNotFoundException(notFoundMessage);
		}
		if (status == 401) {
			throw new UpstreamUnavailableException("GitHub rejected the configured token (HTTP 401).");
		}
		if (status >= 500) {
			return Attempt.retry(null, new UpstreamUnavailableException("GitHub is unavailable (HTTP " + status + ")."));
		}
		throw new UpstreamUnavailableException("GitHub refused the request (HTTP " + status + ").");
	}

	/** Every redirect hop must stay on the allowlist (and https, unless explicitly relaxed). */
	private URI checkedRedirect(URI from, URI target) {
		String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
		String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
		boolean schemeOk = scheme.equals("https") || (!properties.requireHttps() && scheme.equals("http"));
		if (!schemeOk || target.getRawUserInfo() != null || !allowedHosts.contains(host)) {
			log.warn("Refusing GitHub redirect from {} to non-allowlisted target {}", describe(from), host);
			throw new UpstreamUnavailableException("GitHub redirected to a host that is not allowed.");
		}
		return target;
	}

	private void addHeaders(HttpHeaders headers, boolean authenticated) {
		headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
		headers.set("X-GitHub-Api-Version", properties.apiVersion());
		headers.set(HttpHeaders.USER_AGENT, "repo-intelligence-platform");
		if (authenticated) {
			headers.setBearerAuth(properties.token());
		}
	}

	private void pause(Duration delay) {
		try {
			sleeper.sleep(delay);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UpstreamUnavailableException("Interrupted while waiting to retry GitHub.");
		}
	}

	// ---- helpers -------------------------------------------------------------------------------

	private UriComponentsBuilder baseBuilder() {
		return UriComponentsBuilder.fromUriString(properties.apiBaseUrl());
	}

	private URI apiUri(String... segments) {
		return baseBuilder().pathSegment(segments).build().encode().toUri();
	}

	/** Host and path only: never headers, query strings or credentials. */
	private static String describe(URI uri) {
		return uri.getHost() + uri.getPath();
	}

	private static UpstreamUnavailableException unexpectedResponse() {
		return new UpstreamUnavailableException("GitHub returned an unexpected response.");
	}

	private static void writeLimited(HttpHeaders headers, InputStream body, Path target, long maxBytes)
			throws IOException {
		long declared = headers.getContentLength();
		if (declared > maxBytes) {
			throw tooLarge(maxBytes);
		}
		try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			copyLimited(body, out, maxBytes);
		}
		catch (IOException | RuntimeException ex) {
			Files.deleteIfExists(target);
			throw ex;
		}
	}

	/** Copies at most {@code maxBytes}; throws as soon as the limit would be exceeded. */
	static long copyLimited(InputStream in, OutputStream out, long maxBytes) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		long total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > maxBytes) {
				throw tooLarge(maxBytes);
			}
			out.write(buffer, 0, read);
		}
		return total;
	}

	private static AnalysisLimitException tooLarge(long maxBytes) {
		return new AnalysisLimitException(
				"Repository archive exceeds the maximum allowed size of " + (maxBytes / (1024 * 1024)) + " MB.");
	}

	// ---- response DTOs -------------------------------------------------------------------------

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record RepositoryResponse(long id, String name, Owner owner,
			@JsonProperty("default_branch") String defaultBranch, String visibility,
			@JsonProperty("private") boolean isPrivate, long size, boolean archived, boolean disabled) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Owner(String login) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record CommitResponse(String sha) {
	}

}
