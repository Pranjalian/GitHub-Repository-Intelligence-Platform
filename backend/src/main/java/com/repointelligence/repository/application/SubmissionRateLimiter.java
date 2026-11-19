package com.repointelligence.repository.application;

import com.repointelligence.common.exception.SubmissionRateLimitException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * In-memory token bucket guarding {@code POST /analyze}. One global bucket: this is a single-user
 * local tool until authentication exists.
 */
@Component
public class SubmissionRateLimiter {

	private final Clock clock;

	private final double capacity;

	private final double refillPerSecond;

	private double tokens;

	private Instant lastRefill;

	public SubmissionRateLimiter(SubmissionProperties properties, Clock clock) {
		this.clock = clock;
		SubmissionProperties.RateLimit limit = properties.rateLimit();
		this.capacity = limit.capacity();
		this.refillPerSecond = limit.capacity() / (limit.refillPeriod().toMillis() / 1000.0);
		this.tokens = this.capacity;
		this.lastRefill = clock.instant();
	}

	/** Takes one token or throws with the time until one is available. */
	public synchronized void acquireOrThrow() {
		Instant now = clock.instant();
		double elapsedSeconds = Math.max(0, (now.toEpochMilli() - lastRefill.toEpochMilli()) / 1000.0);
		tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
		lastRefill = now;
		if (tokens >= 1) {
			tokens -= 1;
			return;
		}
		long retryAfter = Math.max(1, (long) Math.ceil((1 - tokens) / refillPerSecond));
		throw new SubmissionRateLimitException(retryAfter);
	}

}
