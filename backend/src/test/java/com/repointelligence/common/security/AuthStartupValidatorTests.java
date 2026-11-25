package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Fail closed: a deployment must never come up open or with an account nobody can use. */
class AuthStartupValidatorTests {

	private static final String VALID_HASH = new BCryptPasswordEncoder(4).encode("pw");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AuthProperties.class)
	static class Config {

		@org.springframework.context.annotation.Bean
		AuthStartupValidator validator(AuthProperties properties) {
			return new AuthStartupValidator(properties);
		}

	}

	@Test
	void disabledAuthNeedsNoAccount() {
		runner.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void validAccountStarts() {
		runner.withPropertyValues("auth.enabled=true", "auth.username=operator", "auth.password-hash=" + VALID_HASH)
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void missingHashRefusesToStartAndNamesTheVariable() {
		runner.withPropertyValues("auth.enabled=true", "auth.username=operator")
			.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
				.hasMessageContaining("AUTH_PASSWORD_HASH")
				.hasMessageContaining("missing"));
	}

	@Test
	void blankUsernameRefusesToStart() {
		runner.withPropertyValues("auth.enabled=true", "auth.username= ", "auth.password-hash=" + VALID_HASH)
			.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
				.hasMessageContaining("AUTH_USERNAME"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "plain-text-password-1234", "$2a$12$tooShort", "$1$abcdefgh$abcdefghijklmnopqrstuv",
			"$2a$1$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "{bcrypt}x" })
	void malformedHashRefusesToStartAndTheMessageNeverContainsTheValue(String value) {
		runner.withPropertyValues("auth.enabled=true", "auth.username=operator", "auth.password-hash=" + value)
			.run(context -> {
				assertThat(context).hasFailed();
				Throwable failure = context.getStartupFailure();
				for (Throwable t = failure; t != null; t = t.getCause()) {
					assertThat(String.valueOf(t.getMessage())).doesNotContain(value);
				}
				assertThat(context.getStartupFailure().getMessage() + rootMessage(failure))
					.contains("AUTH_PASSWORD_HASH");
			});
	}

	@Test
	void acceptsTheHashVariantsOtherToolsProduce() {
		for (String prefix : new String[] { "$2a$12$", "$2b$12$", "$2y$12$" }) {
			String hash = prefix + "a".repeat(53);
			assertThatCode(() -> new AuthStartupValidator(
					new AuthProperties(true, "operator", hash, new AuthProperties.Session(Duration.ofHours(2), Duration.ofHours(12)),
							new AuthProperties.Throttle(5, Duration.ofMinutes(15), 1000)))
				.validate()).doesNotThrowAnyException();
		}
	}

	@Test
	void propertiesNeverPrintTheirSecrets() {
		AuthProperties properties = new AuthProperties(true, "operator", VALID_HASH,
				new AuthProperties.Session(Duration.ofHours(2), Duration.ofHours(12)),
				new AuthProperties.Throttle(5, Duration.ofMinutes(15), 1000));

		assertThat(properties.toString()).doesNotContain(VALID_HASH).doesNotContain("operator").contains("passwordHash=<set>");
	}

	private static String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root.getMessage();
	}

}
