package com.repointelligence.ai.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The one HTTP path every provider adapter uses: an outbound allowlist for the base URL, no redirects,
 * a timeout per attempt, a capped response size and at most {@code maxRetries} retries, and only for
 * transient failures (connection errors and 5xx). Timeouts and 4xx (including 429) are never retried:
 * a slow model stays slow, and a quota error must not become a retry storm.
 */
public final class ProviderHttp {

	/** Largest response body read from a provider. */
	static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

	private final String providerId;

	private final URI baseUri;

	private final Duration timeout;

	private final int maxRetries;

	private final Duration retryBackoff;

	private final HttpClient client;

	public ProviderHttp(String providerId, URI baseUri, Duration timeout, int maxRetries, Duration retryBackoff) {
		this.providerId = providerId;
		this.baseUri = baseUri;
		this.timeout = timeout;
		this.maxRetries = maxRetries;
		this.retryBackoff = retryBackoff;
		Duration connect = timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10);
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(connect)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	/**
	 * Parses and checks a configured base URL: the host must be in the allowlist and, when
	 * {@code requireHttps}, the scheme must be https. Throws {@link IllegalStateException} with a readable
	 * reason otherwise; adapters turn that into a NOT_CONFIGURED health status.
	 */
	public static URI checkedBaseUri(String providerId, String baseUrl, List<String> allowedHosts,
			boolean requireHttps) {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalStateException(providerId + ": base-url is not set");
		}
		URI uri;
		try {
			uri = URI.create(baseUrl.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(providerId + ": base-url is not a valid URL");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http")) {
			throw new IllegalStateException(providerId + ": base-url must use http or https");
		}
		if (requireHttps && !scheme.equals("https")) {
			throw new IllegalStateException(providerId + ": base-url must use https");
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalStateException(providerId + ": base-url must not contain credentials");
		}
		String host = uri.getHost();
		if (host == null || allowedHosts.stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
			throw new IllegalStateException(providerId + ": host " + host + " is not in ai.providers." + providerId
					+ ".allowed-hosts");
		}
		String path = uri.getPath() == null ? "" : uri.getPath();
		return URI.create(scheme + "://" + uri.getRawAuthority() + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path));
	}

	public URI resolve(String path) {
		return URI.create(baseUri.toString() + path);
	}

	/** A finished exchange; {@code body} is at most {@link #MAX_RESPONSE_BYTES}. */
	public record Result(int status, String body, HttpHeaders headers, long latencyMs) {

		public boolean success() {
			return status >= 200 && status < 300;
		}

		/** Retry-After as seconds or an HTTP date, if present. */
		public Instant retryAfter(Instant now) {
			String value = headers.firstValue("Retry-After").orElse(null);
			if (value == null || value.isBlank()) {
				return null;
			}
			try {
				return now.plusSeconds(Long.parseLong(value.trim()));
			}
			catch (NumberFormatException ex) {
				try {
					return java.time.ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				}
				catch (RuntimeException ignored) {
					return null;
				}
			}
		}

	}

	/** Why an exchange produced no HTTP response. */
	public enum FailureKind {

		TIMEOUT, UNREACHABLE, IO, INTERRUPTED

	}

	/** No HTTP response: timeout, connection failure or I/O error. The message never contains a key. */
	public static final class CallFailure extends Exception {

		private final FailureKind kind;

		CallFailure(FailureKind kind, String message) {
			super(message);
			this.kind = kind;
		}

		public FailureKind kind() {
			return kind;
		}

	}

	/** Sends with the timeout and retry policy. Headers set on the builder (e.g. auth) are never logged. */
	public Result send(HttpRequest.Builder builder) throws CallFailure {
		HttpRequest request = builder.timeout(timeout).build();
		CallFailure lastFailure = null;
		Result lastResult = null;
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			long started = System.nanoTime();
			try {
				HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
				String body;
				try (InputStream in = response.body()) {
					body = readLimited(in);
				}
				lastResult = new Result(response.statusCode(), body, response.headers(),
						(System.nanoTime() - started) / 1_000_000);
				lastFailure = null;
				if (response.statusCode() < 500) {
					return lastResult;
				}
			}
			catch (HttpTimeoutException ex) {
				throw new CallFailure(FailureKind.TIMEOUT,
						providerId + " did not answer within " + timeout.toSeconds() + " s");
			}
			catch (ConnectException ex) {
				lastFailure = new CallFailure(FailureKind.UNREACHABLE, providerId + " is not reachable at " + baseUri.getHost());
				lastResult = null;
			}
			catch (IOException ex) {
				lastFailure = new CallFailure(FailureKind.IO, providerId + " request failed (" + ex.getClass().getSimpleName() + ")");
				lastResult = null;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new CallFailure(FailureKind.INTERRUPTED, providerId + " request was interrupted");
			}
			if (attempt < maxRetries) {
				try {
					Thread.sleep(retryBackoff.toMillis() * (attempt + 1));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new CallFailure(FailureKind.INTERRUPTED, providerId + " request was interrupted");
				}
			}
		}
		if (lastResult != null) {
			return lastResult;
		}
		throw lastFailure;
	}

	private static String readLimited(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > MAX_RESPONSE_BYTES) {
				throw new IOException("response larger than " + MAX_RESPONSE_BYTES + " bytes");
			}
			out.write(buffer, 0, read);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

}
