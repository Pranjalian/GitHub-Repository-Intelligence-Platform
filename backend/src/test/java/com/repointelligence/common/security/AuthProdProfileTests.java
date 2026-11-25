package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.support.AuthTestClient;
import com.repointelligence.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The deployment configuration: {@code prod} profile with the login on (what Railway runs). */
@Import(EmbeddedPostgresConfig.class)
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "auth.enabled=true", "auth.username=operator" })
class AuthProdProfileTests {

	@DynamicPropertySource
	static void account(DynamicPropertyRegistry registry) {
		registry.add("auth.password-hash", () -> new BCryptPasswordEncoder(4).encode("prod-test-password"));
	}

	@LocalServerPort
	int port;

	@Test
	void healthIsTheOnlyOpenPathAndNothingElseDocumentsTheApi() {
		AuthTestClient anonymous = new AuthTestClient(port);
		assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
		assertThat(anonymous.get("/api/v1/repositories").status()).isEqualTo(401);
		for (String path : new String[] { "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/actuator/info",
				"/actuator/env" }) {
			assertThat(anonymous.get(path).status()).as("signed out: " + path).isNotEqualTo(200);
		}

		AuthTestClient signedIn = new AuthTestClient(port);
		signedIn.get("/api/v1/auth/session");
		assertThat(signedIn.login("operator", "prod-test-password").status()).isEqualTo(200);
		for (String path : new String[] { "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/actuator/info",
				"/actuator/env", "/actuator/beans" }) {
			assertThat(signedIn.get(path).status()).as("even signed in, " + path + " does not exist in prod")
				.isEqualTo(404);
		}
		assertThat(signedIn.get("/api/v1/repositories").status()).isEqualTo(200);
	}

	@Test
	void sessionCookieIsSecureHttpOnlyAndSameSiteLax() {
		AuthTestClient client = new AuthTestClient(port);
		client.get("/api/v1/auth/session");
		String cookie = client.login("operator", "prod-test-password").setCookie("REPOINTEL_SESSION");

		assertThat(cookie).isNotNull().contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
		assertThat(cookie).as("host-only: no Domain attribute").doesNotContain("Domain=");
	}

}
