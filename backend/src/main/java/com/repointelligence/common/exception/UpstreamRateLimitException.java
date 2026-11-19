package com.repointelligence.common.exception;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;

/** An upstream rate limit is exhausted. Fail fast; callers must never sleep until the reset. */
public class UpstreamRateLimitException extends ApiException {

	private final Instant resetAt;

	public UpstreamRateLimitException(String message, Instant resetAt) {
		super(HttpStatus.TOO_MANY_REQUESTS, message);
		this.resetAt = resetAt;
	}

	/** When the limit resets, or {@code null} if unknown. */
	public Instant getResetAt() {
		return resetAt;
	}

	/** Whole seconds until reset, at least 1, or {@code null} if the reset time is unknown. */
	public Long retryAfterSeconds(Instant now) {
		if (resetAt == null) {
			return null;
		}
		return Math.max(1L, Duration.between(now, resetAt).toSeconds());
	}

}
