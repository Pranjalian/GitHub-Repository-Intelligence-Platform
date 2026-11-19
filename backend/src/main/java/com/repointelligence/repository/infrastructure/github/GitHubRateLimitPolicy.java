package com.repointelligence.repository.infrastructure.github;

import com.repointelligence.common.exception.ApiException;
import com.repointelligence.common.exception.UpstreamRateLimitException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Decides what to do with GitHub rate-limit responses. A primary limit that is exhausted fails
 * fast with the reset time: a worker thread is never parked until the reset. Secondary limits and
 * transient errors back off with a short, bounded delay.
 */
@Component
public class GitHubRateLimitPolicy {

	static final String REMAINING = "X-RateLimit-Remaining";

	static final String RESET = "X-RateLimit-Reset";

	private final GitHubProperties.Retry retry;

	public GitHubRateLimitPolicy(GitHubProperties properties) {
		this.retry = properties.retry();
	}

	/** Outcome for a response that may be a rate-limit signal. */
	public sealed interface Decision {

		/** Not a rate-limit response. */
		record NotRateLimited() implements Decision {
		}

		/** Give up immediately. */
		record FailFast(ApiException exception) implements Decision {
		}

		/** Wait {@code delay} and try again; if attempts run out, throw {@code ifExhausted}. */
		record RetryAfter(Duration delay, ApiException ifExhausted) implements Decision {
		}

	}

	public Decision evaluate(int status, HttpHeaders headers, Instant now) {
		if (status != 403 && status != 429) {
			return new Decision.NotRateLimited();
		}
		String remaining = headers.getFirst(REMAINING);
		Instant resetAt = parseEpoch(headers.getFirst(RESET));
		if ("0".equals(remaining)) {
			return new Decision.FailFast(new UpstreamRateLimitException(
					"GitHub API rate limit reached" + (resetAt != null ? "; resets at " + resetAt : "")
							+ ". Configure GITHUB_TOKEN to raise the limit.",
					resetAt));
		}
		Long retryAfterSeconds = parseSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER));
		if (retryAfterSeconds != null) {
			Duration wait = Duration.ofSeconds(Math.max(0, retryAfterSeconds));
			Instant retryAt = now.plus(wait);
			UpstreamRateLimitException limit = new UpstreamRateLimitException(
					"GitHub asked clients to slow down (secondary rate limit)", retryAt);
			if (wait.compareTo(retry.maxBackoff()) > 0) {
				return new Decision.FailFast(limit);
			}
			return new Decision.RetryAfter(wait, limit);
		}
		if (status == 429) {
			return new Decision.RetryAfter(null,
					new UpstreamRateLimitException("GitHub is rate limiting requests", null));
		}
		// A 403 with no rate-limit hint is a refusal, not a limit.
		return new Decision.NotRateLimited();
	}

	/** Exponential backoff for attempt {@code attempt} (1-based), capped at the configured maximum. */
	public Duration backoff(int attempt) {
		long millis = retry.initialBackoff().toMillis() * (1L << Math.min(Math.max(attempt - 1, 0), 20));
		return Duration.ofMillis(Math.min(millis, retry.maxBackoff().toMillis()));
	}

	private static Instant parseEpoch(String value) {
		Long seconds = parseSeconds(value);
		return seconds == null ? null : Instant.ofEpochSecond(seconds);
	}

	private static Long parseSeconds(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
