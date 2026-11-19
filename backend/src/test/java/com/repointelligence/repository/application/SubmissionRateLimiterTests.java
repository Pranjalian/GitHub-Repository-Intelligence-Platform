package com.repointelligence.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.common.exception.SubmissionRateLimitException;
import com.repointelligence.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubmissionRateLimiterTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));

	private SubmissionRateLimiter limiter(int capacity, Duration refill) {
		return new SubmissionRateLimiter(new SubmissionProperties(10, new SubmissionProperties.RateLimit(capacity, refill)),
				clock);
	}

	@Test
	void allowsUpToCapacityThenRejectsWithRetryAfter() {
		SubmissionRateLimiter limiter = limiter(3, Duration.ofMinutes(1));

		for (int i = 0; i < 3; i++) {
			limiter.acquireOrThrow();
		}

		assertThatThrownBy(limiter::acquireOrThrow).isInstanceOfSatisfying(SubmissionRateLimitException.class,
				ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(20));
	}

	@Test
	void refillsOverTime() {
		SubmissionRateLimiter limiter = limiter(3, Duration.ofMinutes(1));
		for (int i = 0; i < 3; i++) {
			limiter.acquireOrThrow();
		}

		clock.advance(Duration.ofSeconds(20));

		assertThatCode(limiter::acquireOrThrow).doesNotThrowAnyException();
		assertThatThrownBy(limiter::acquireOrThrow).isInstanceOf(SubmissionRateLimitException.class);
	}

	@Test
	void neverRefillsBeyondCapacity() {
		SubmissionRateLimiter limiter = limiter(2, Duration.ofMinutes(1));

		clock.advance(Duration.ofHours(5));
		limiter.acquireOrThrow();
		limiter.acquireOrThrow();

		assertThatThrownBy(limiter::acquireOrThrow).isInstanceOf(SubmissionRateLimitException.class);
	}

}
