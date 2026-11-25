package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.AuthTestClient;
import com.repointelligence.support.EmbeddedPostgresConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Step 8.3: the login is a gate in front of the existing protections, not a replacement. Every AI, search, Q&A and
 * impact endpoint is behind it, and the analysis rate limit still applies to a signed-in user. Real server, login on.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "auth.enabled=true", "auth.username=operator", "repository.submission.rate-limit.capacity=2",
				"repository.submission.rate-limit.refill-period=PT1H" })
class AuthHardeningTests {

	private static final String PASSWORD = "hardening-test-password";

	private static final String REPO = "/api/v1/repositories/" + UUID.randomUUID();

	private static final String SNAPSHOT = REPO + "/snapshots/" + UUID.randomUUID();

	@DynamicPropertySource
	static void account(DynamicPropertyRegistry registry) {
		registry.add("auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
	}

	@LocalServerPort
	int port;

	@Test
	void everyAiSearchQaImpactAndIndexEndpointIsBehindTheLogin() {
		AuthTestClient anonymous = new AuthTestClient(port);
		anonymous.get("/api/v1/auth/session"); // CSRF cookie, so the 401 (not a 403) is what is being tested

		for (String path : new String[] { "/api/v1/ai/providers", "/api/v1/ai/usage", SNAPSHOT + "/search?q=x",
				SNAPSHOT + "/index", SNAPSHOT + "/impact-analysis/symbols", SNAPSHOT + "/architecture", REPO + "/snapshots",
				SNAPSHOT + "/findings", SNAPSHOT + "/dependencies" }) {
			assertThat(anonymous.get(path).status()).as("GET " + path).isEqualTo(401);
		}
		for (String path : new String[] { SNAPSHOT + "/insights", SNAPSHOT + "/questions", SNAPSHOT + "/index",
				SNAPSHOT + "/impact-analysis", SNAPSHOT + "/impact-analysis/suggestions", "/api/v1/repositories/analyze",
				"/api/v1/analysis-runs/" + UUID.randomUUID() + "/cancel" }) {
			assertThat(anonymous.post(path, "{}").status()).as("POST " + path).isEqualTo(401);
		}
	}

	@Test
	void theAnalysisRateLimitStillAppliesAfterLoginAndStrangersDoNotSpendIt() {
		AuthTestClient stranger = new AuthTestClient(port);
		stranger.get("/api/v1/auth/session");
		for (int i = 0; i < 10; i++) {
			assertThat(stranger.post("/api/v1/repositories/analyze", "{\"url\":\"not a url\"}").status()).isEqualTo(401);
		}

		AuthTestClient operator = new AuthTestClient(port);
		operator.get("/api/v1/auth/session");
		assertThat(operator.login("operator", PASSWORD).status()).isEqualTo(200);
		// the limiter runs before the URL is parsed, so an unparseable URL shows which requests it counted
		assertThat(operator.post("/api/v1/repositories/analyze", "{\"url\":\"not a url\"}").status()).isEqualTo(400);
		assertThat(operator.post("/api/v1/repositories/analyze", "{\"url\":\"not a url\"}").status()).isEqualTo(400);
		AuthTestClient.Reply limited = operator.post("/api/v1/repositories/analyze", "{\"url\":\"not a url\"}");
		assertThat(limited.status()).isEqualTo(429);
		assertThat(Long.parseLong(limited.header("Retry-After"))).isPositive();
		assertThat(JsonPath.<Integer>read(limited.body(), "$.retryAfterSeconds")).isPositive();
	}

}
