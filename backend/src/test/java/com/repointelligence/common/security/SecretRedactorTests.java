package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecretRedactorTests {

	private static final String MASK = SecretRedactor.MASK;

	@ParameterizedTest
	@ValueSource(strings = { "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "gho_abcdefghijklmnopqrstuvwxyz0123456789",
			"github_pat_11ABCDEFG0abcdefghijkl_mnopqrstuvwxyz0123456789", "AKIAIOSFODNN7EXAMPLE", "ASIAIOSFODNN7EXAMPLE",
			"xoxb-1234567890-abcdefghij", "sk_live_abcdefghijklmnop1234",
			"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c" })
	void masksStandaloneTokens(String secret) {
		String redacted = SecretRedactor.redact("before " + secret + " after");
		assertThat(redacted).isEqualTo("before " + MASK + " after");
	}

	@Test
	void masksBearerAndBasicCredentialsButKeepsTheScheme() {
		assertThat(SecretRedactor.redact("Authorization: Bearer abc123def456ghi789")).isEqualTo("Authorization: Bearer " + MASK);
		assertThat(SecretRedactor.redact("Authorization: Basic dXNlcjpwYXNzd29yZA==")).isEqualTo("Authorization: Basic " + MASK);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "password=hunter2|password=" + MASK, "db.password = hunter2|db.password = " + MASK,
			"DB_PASSWORD=hunter2|DB_PASSWORD=" + MASK, "api_key: abcd1234|api_key: " + MASK, "apiKey=abcd1234|apiKey=" + MASK,
			"client_secret: s3cr3t!|client_secret: " + MASK, "token=abc.def-ghi|token=" + MASK })
	void masksKeyValueFormsAndPreservesTheKey(String input, String expected) {
		assertThat(SecretRedactor.redact(input)).isEqualTo(expected);
	}

	@Test
	void masksJsonAndQuotedForms() {
		assertThat(SecretRedactor.redact("{\"password\": \"hunter2\", \"user\": \"bob\"}"))
			.isEqualTo("{\"password\": \"" + MASK + "\", \"user\": \"bob\"}");
		assertThat(SecretRedactor.redact("secret: 'hunter2'")).isEqualTo("secret: '" + MASK + "'");
		assertThat(SecretRedactor.redact("spring:\n  datasource:\n    password: hunter2\n    username: bob"))
			.isEqualTo("spring:\n  datasource:\n    password: " + MASK + "\n    username: bob");
	}

	@Test
	void masksPrivateKeyBlocks() {
		String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\nabcdef\n-----END RSA PRIVATE KEY-----";
		assertThat(SecretRedactor.redact("key:\n" + pem + "\nend")).isEqualTo("key:\n" + MASK + "\nend");
		assertThat(SecretRedactor.redact("-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg")).isEqualTo(MASK);
	}

	@Test
	void leavesOrdinaryTextUntouched() {
		String text = "Analysis completed for repository spring-petclinic in 1532 ms; the token count is unchanged. "
				+ "See https://github.com/o/r/blob/abc123/src/Main.java#L10-L20 and password policy docs.";
		assertThat(SecretRedactor.redact(text)).isEqualTo(text);
		assertThat(SecretRedactor.redact(null)).isNull();
		assertThat(SecretRedactor.redact("")).isEmpty();
	}

	@Test
	void isIdempotent() {
		String once = SecretRedactor.redact("password=hunter2 token ghp_abcdefghijklmnopqrstuvwxyz0123456789");
		assertThat(SecretRedactor.redact(once)).isEqualTo(once);
	}

}
