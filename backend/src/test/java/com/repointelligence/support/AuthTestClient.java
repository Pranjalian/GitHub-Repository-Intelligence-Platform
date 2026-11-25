package com.repointelligence.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal browser stand-in for the login tests: real HTTP against the running server, a cookie jar (name to
 * value) and the SPA's CSRF behaviour (copy the {@code XSRF-TOKEN} cookie into {@code X-XSRF-TOKEN} on
 * state-changing calls). It exists because MockMvc has no servlet container: cookie attributes, session ids
 * and invalidation only mean something over a real connection.
 */
public final class AuthTestClient {

	/** What came back. */
	public record Reply(int status, String body, java.net.http.HttpHeaders headers) {

		public List<String> setCookies() {
			return headers.allValues("Set-Cookie");
		}

		/** The full {@code Set-Cookie} line for a cookie name, or null. */
		public String setCookie(String name) {
			return setCookies().stream().filter(line -> line.startsWith(name + "=")).findFirst().orElse(null);
		}

		public String header(String name) {
			return headers.firstValue(name).orElse(null);
		}

	}

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private final String base;

	private final Map<String, String> cookies = new LinkedHashMap<>();

	public AuthTestClient(int port) {
		this.base = "http://localhost:" + port;
	}

	public String cookie(String name) {
		return cookies.get(name);
	}

	public void setCookie(String name, String value) {
		cookies.put(name, value);
	}

	public Reply get(String path, String... extraHeaders) {
		return send("GET", path, null, false, extraHeaders);
	}

	/** A POST that sends the CSRF header like the SPA does. */
	public Reply post(String path, String jsonBody, String... extraHeaders) {
		return send("POST", path, jsonBody, true, extraHeaders);
	}

	/** A POST without the CSRF header. */
	public Reply postWithoutCsrf(String path, String jsonBody, String... extraHeaders) {
		return send("POST", path, jsonBody, false, extraHeaders);
	}

	public Reply login(String username, String password) {
		return post("/api/v1/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
	}

	private Reply send(String method, String path, String body, boolean csrf, String... extraHeaders) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (body != null) {
			request.header("Content-Type", "application/json");
		}
		if (!cookies.isEmpty()) {
			List<String> pairs = new ArrayList<>();
			cookies.forEach((name, value) -> pairs.add(name + "=" + value));
			request.header("Cookie", String.join("; ", pairs));
		}
		if (csrf && cookies.containsKey("XSRF-TOKEN")) {
			request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
		}
		for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
			request.header(extraHeaders[i], extraHeaders[i + 1]);
		}
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			absorbCookies(response.headers().allValues("Set-Cookie"));
			return new Reply(response.statusCode(), response.body(), response.headers());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private void absorbCookies(List<String> setCookieLines) {
		for (String line : setCookieLines) {
			String[] parts = line.split(";");
			String[] pair = parts[0].split("=", 2);
			String name = pair[0].trim();
			String value = pair.length > 1 ? pair[1] : "";
			boolean expired = line.toLowerCase().contains("max-age=0") || value.isEmpty();
			if (expired) {
				cookies.remove(name);
			}
			else {
				cookies.put(name, value);
			}
		}
	}

}
