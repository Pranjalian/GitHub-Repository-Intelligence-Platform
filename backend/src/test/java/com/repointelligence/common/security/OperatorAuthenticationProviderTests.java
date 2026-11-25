package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class OperatorAuthenticationProviderTests {

	private static final String PASSWORD = "correct-horse-battery-staple";

	// cost 10: slow enough that BCrypt dominates everything else when timing
	private static final String HASH = new BCryptPasswordEncoder(10).encode(PASSWORD);

	private final OperatorAuthenticationProvider provider = new OperatorAuthenticationProvider(
			new AuthProperties(true, "operator", HASH, new AuthProperties.Session(Duration.ofHours(2), Duration.ofHours(12)),
					new AuthProperties.Throttle(5, Duration.ofMinutes(15), 1000)));

	@Test
	void acceptsTheConfiguredAccountAndGrantsTheOperatorRole() {
		Authentication result = provider.authenticate(token("operator", PASSWORD));

		assertThat(result.isAuthenticated()).isTrue();
		assertThat(result.getName()).isEqualTo("operator");
		assertThat(result.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
		assertThat(result.getCredentials()).as("the password is not kept").isNull();
	}

	@Test
	void everyKindOfFailureLooksTheSame() {
		for (String[] attempt : new String[][] { { "operator", "wrong" }, { "nobody", PASSWORD }, { "nobody", "wrong" },
				{ "OPERATOR", PASSWORD }, { "", "" }, { "operator", "x".repeat(500) }, { "operator", PASSWORD + "x" } }) {
			assertThatThrownBy(() -> provider.authenticate(token(attempt[0], attempt[1])))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessage("Invalid username or password");
		}
	}

	@Test
	void aWrongUsernameCostsAboutAsMuchAsAWrongPassword() {
		provider.authenticate(token("operator", PASSWORD)); // warm up
		List<Long> wrongUser = new ArrayList<>();
		List<Long> wrongPassword = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			wrongUser.add(time("nobody", PASSWORD));
			wrongPassword.add(time("operator", "wrong-" + i));
		}

		double ratio = (double) median(wrongUser) / median(wrongPassword);
		// A BCrypt check is roughly 50-100 ms here; skipping it for unknown users would give a ratio near 0.
		assertThat(ratio).as("median wrong-user time / median wrong-password time").isBetween(0.4, 2.5);
	}

	private long time(String username, String password) {
		long start = System.nanoTime();
		try {
			provider.authenticate(token(username, password));
		}
		catch (BadCredentialsException expected) {
			// expected
		}
		return System.nanoTime() - start;
	}

	private static long median(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.get(sorted.size() / 2);
	}

	private static Authentication token(String username, String password) {
		return UsernamePasswordAuthenticationToken.unauthenticated(username, password);
	}

}
