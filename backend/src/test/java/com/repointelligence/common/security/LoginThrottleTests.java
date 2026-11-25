package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.common.exception.LoginThrottledException;
import com.repointelligence.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginThrottleTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));

	private final LoginThrottle throttle = new LoginThrottle(
			new AuthProperties.Throttle(5, Duration.ofMinutes(15), 100), clock);

	@Test
	void fiveFailuresLockTheKeyForTheWindowAndRetryAfterCountsDown() {
		for (int i = 0; i < 4; i++) {
			throttle.recordFailure("10.0.0.1", "operator");
			assertThat(throttle.retryAfterSeconds("10.0.0.1", "operator")).isZero();
		}
		throttle.recordFailure("10.0.0.1", "operator");

		assertThat(throttle.retryAfterSeconds("10.0.0.1", "operator")).isEqualTo(900);
		clock.advance(Duration.ofMinutes(5));
		assertThat(throttle.retryAfterSeconds("10.0.0.1", "operator")).isEqualTo(600);
		assertThatThrownBy(() -> throttle.checkAllowed("10.0.0.1", "operator"))
			.isInstanceOf(LoginThrottledException.class)
			.satisfies(ex -> assertThat(((LoginThrottledException) ex).getRetryAfterSeconds()).isEqualTo(600));
	}

	@Test
	void theLockExpiresAfterTheWindow() {
		for (int i = 0; i < 5; i++) {
			throttle.recordFailure("10.0.0.1", "operator");
		}
		clock.advance(Duration.ofMinutes(15));

		assertThat(throttle.retryAfterSeconds("10.0.0.1", "operator")).isZero();
		assertThat(throttle.trackedKeys()).isZero();
	}

	@Test
	void failuresOlderThanTheWindowAreForgotten() {
		for (int i = 0; i < 4; i++) {
			throttle.recordFailure("10.0.0.1", "operator");
		}
		clock.advance(Duration.ofMinutes(16));
		throttle.recordFailure("10.0.0.1", "operator");

		assertThat(throttle.retryAfterSeconds("10.0.0.1", "operator")).as("1 recent failure, not 5").isZero();
	}

	@Test
	void keysAreIndependent() {
		for (int i = 0; i < 5; i++) {
			throttle.recordFailure("10.0.0.1", "operator");
		}

		assertThat(throttle.retryAfterSeconds("10.0.0.1", "someone-else")).as("same address is locked").isPositive();
		assertThat(throttle.retryAfterSeconds("10.0.0.2", "operator")).as("same username is locked").isPositive();
		assertThat(throttle.retryAfterSeconds("10.0.0.2", "someone-else")).as("neither shared").isZero();
	}

	@Test
	void usernamesAreNormalisedSoCaseAndPaddingDoNotResetTheCounter() {
		for (int i = 0; i < 5; i++) {
			throttle.recordFailure("10.0.0." + i, i % 2 == 0 ? "Operator" : "  operator ");
		}

		assertThat(throttle.retryAfterSeconds("10.9.9.9", "OPERATOR")).isPositive();
	}

	@Test
	void successClearsBothCounters() {
		for (int i = 0; i < 4; i++) {
			throttle.recordFailure("10.0.0.1", "operator");
		}
		throttle.recordSuccess("10.0.0.1", "operator");
		for (int i = 0; i < 4; i++) {
			throttle.recordFailure("10.0.0.1", "operator");
		}

		assertThat(throttle.retryAfterSeconds("10.0.0.1", "operator")).as("4 + 4 failures, split by a success").isZero();
	}

	@Test
	void trackedKeysAreBoundedSoRandomUsernamesCannotGrowMemory() {
		for (int i = 0; i < 1000; i++) {
			throttle.recordFailure("10.0.0.1", "guess-" + i);
		}

		assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(100);
	}

}
