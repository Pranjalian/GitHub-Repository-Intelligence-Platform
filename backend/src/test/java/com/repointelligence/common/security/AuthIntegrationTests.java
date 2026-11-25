package com.repointelligence.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.AuthTestClient;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.MutableClock;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The login against a real server (real Tomcat, real cookies), with {@code auth.enabled=true}, embedded
 * PostgreSQL and a movable clock. This is the evidence for the exit criterion "every /api/** call except the auth
 * endpoints answers 401 without a session".
 */
@Import({ EmbeddedPostgresConfig.class, AuthIntegrationTests.TestBeans.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "auth.enabled=true", "auth.username=operator" })
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTests {

	static final String PASSWORD = "correct-horse-battery-staple";

	private static final String PROTECTED = "/api/v1/repositories";

	@DynamicPropertySource
	static void account(DynamicPropertyRegistry registry) {
		// A cost-4 hash computed here: fast, and no hash is committed anywhere.
		registry.add("auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
	}

	@LocalServerPort
	int port;

	@Autowired
	MutableClock clock;

	@Autowired
	LoginThrottle throttle;

	AuthTestClient client;

	@BeforeEach
	void freshBrowser() {
		throttle.clear();
		client = new AuthTestClient(port);
	}

	@Test
	void protectedPathsAnswer401ProblemBodyWithoutASessionAndNoBasicChallenge() {
		for (String path : new String[] { PROTECTED, "/api/v1/repositories/stats", "/api/v1/ai/providers",
				"/api/v1/ai/usage", "/api/v1/analysis-runs/" + UUID.randomUUID(), "/swagger-ui.html",
				"/v3/api-docs", "/actuator/info", "/anything-else" }) {
			AuthTestClient.Reply reply = client.get(path);
			assertThat(reply.status()).as(path).isEqualTo(401);
			assertThat(reply.header("WWW-Authenticate")).as("no browser dialog for " + path).isNull();
			assertThat(JsonPath.<Integer>read(reply.body(), "$.status")).isEqualTo(401);
			assertThat(JsonPath.<String>read(reply.body(), "$.detail")).isNotBlank();
			assertThat(JsonPath.<String>read(reply.body(), "$.requestId")).isNotBlank();
			assertThat(reply.header("Content-Type")).contains("json");
		}
	}

	@Test
	void stateChangingCallsWithoutASessionAreRefusedWithoutLeakingWhetherTheTargetExists() {
		client.get("/api/v1/auth/session"); // obtains the CSRF cookie like the SPA
		assertThat(client.post("/api/v1/analysis-runs/" + UUID.randomUUID() + "/cancel", null).status()).isEqualTo(401);
		assertThat(client.post("/api/v1/repositories/analyze", "{\"url\":\"https://github.com/a/b\"}").status())
			.isEqualTo(401);
	}

	@Test
	void healthIsOpenAndOnlyTheLiveStatusIsShown() {
		AuthTestClient.Reply reply = client.get("/actuator/health");
		assertThat(reply.status()).isEqualTo(200);
		assertThat(JsonPath.<String>read(reply.body(), "$.status")).isEqualTo("UP");
		assertThat(reply.body()).doesNotContain("components");
	}

	@Test
	void sessionEndpointIsAlways200AndNeverLeaksTheUsernameWhenSignedOut() {
		AuthTestClient.Reply signedOut = client.get("/api/v1/auth/session");
		assertThat(signedOut.status()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(signedOut.body(), "$.authRequired")).isTrue();
		assertThat(JsonPath.<Boolean>read(signedOut.body(), "$.authenticated")).isFalse();
		assertThat(signedOut.body()).doesNotContain("operator").doesNotContain("username");
		assertThat(signedOut.header("Cache-Control")).isEqualTo("no-store");
		assertThat(signedOut.setCookie("XSRF-TOKEN")).isNotNull();

		// an unknown or garbage session cookie is just "signed out"
		client.setCookie("REPOINTEL_SESSION", "0123456789ABCDEF");
		AuthTestClient.Reply stale = client.get("/api/v1/auth/session");
		assertThat(stale.status()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(stale.body(), "$.authenticated")).isFalse();

		client.login("operator", PASSWORD);
		AuthTestClient.Reply signedIn = client.get("/api/v1/auth/session");
		assertThat(signedIn.status()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(signedIn.body(), "$.authenticated")).isTrue();
		assertThat(JsonPath.<String>read(signedIn.body(), "$.username")).isEqualTo("operator");
	}

	@Test
	void loginGivesAHttpOnlySessionCookieAndOpensTheApi() {
		client.get("/api/v1/auth/session");
		AuthTestClient.Reply login = client.login("operator", PASSWORD);

		assertThat(login.status()).isEqualTo(200);
		assertThat(login.header("Cache-Control")).isEqualTo("no-store");
		assertThat(JsonPath.<String>read(login.body(), "$.username")).isEqualTo("operator");
		String cookie = login.setCookie("REPOINTEL_SESSION");
		assertThat(cookie).isNotNull().contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
		assertThat(cookie).as("a plain-HTTP test request: Secure is the prod profile's job").doesNotContain("Secure");
		assertThat(client.get(PROTECTED).status()).isEqualTo(200);
	}

	@Test
	void csrfCookieIsReadableByScriptsAndSameSite() {
		String cookie = client.get("/api/v1/auth/session").setCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull().doesNotContain("HttpOnly").contains("SameSite=Lax");
	}

	@Test
	void loginStartsANewSessionIdAndTheOldOneIsNotAccepted() {
		client.get("/api/v1/auth/session", "X-Test-Plant-Session", "yes");
		String planted = client.cookie("REPOINTEL_SESSION");
		assertThat(planted).as("the test filter planted a pre-login session").isNotBlank();

		client.login("operator", PASSWORD);
		String afterLogin = client.cookie("REPOINTEL_SESSION");
		assertThat(afterLogin).isNotBlank().isNotEqualTo(planted);
		assertThat(client.get(PROTECTED).status()).isEqualTo(200);

		AuthTestClient attacker = new AuthTestClient(port);
		attacker.setCookie("REPOINTEL_SESSION", planted);
		assertThat(attacker.get(PROTECTED).status()).as("the pre-login id must not be a valid session").isEqualTo(401);
	}

	@Test
	void wrongPasswordAndUnknownUserAreIndistinguishable() {
		client.get("/api/v1/auth/session");
		AuthTestClient.Reply wrongPassword = client.login("operator", "not-the-password");
		throttle.clear();
		AuthTestClient.Reply unknownUser = client.login("nobody", PASSWORD);

		assertThat(wrongPassword.status()).isEqualTo(401);
		assertThat(unknownUser.status()).isEqualTo(401);
		assertThat(JsonPath.<String>read(wrongPassword.body(), "$.detail")).isEqualTo("Invalid username or password");
		assertThat(JsonPath.<String>read(unknownUser.body(), "$.detail")).isEqualTo("Invalid username or password");
		assertThat(stripVolatile(wrongPassword.body())).isEqualTo(stripVolatile(unknownUser.body()));
		assertThat(wrongPassword.setCookie("REPOINTEL_SESSION")).isNull();
		assertThat(unknownUser.setCookie("REPOINTEL_SESSION")).isNull();
	}

	@Test
	void malformedLoginBodiesAre400AndDoNotCountAsFailures() {
		client.get("/api/v1/auth/session");
		assertThat(client.post("/api/v1/auth/login", "{not json").status()).isEqualTo(400);
		assertThat(client.post("/api/v1/auth/login", "{\"username\":\"\",\"password\":\"\"}").status()).isEqualTo(400);
		assertThat(client.post("/api/v1/auth/login", "{\"username\":\"operator\"}").status()).isEqualTo(400);
		assertThat(throttle.retryAfterSeconds("127.0.0.1", "operator")).isZero();
		for (int i = 0; i < 4; i++) {
			assertThat(client.login("operator", "wrong-" + i).status()).isEqualTo(401);
		}
		assertThat(client.login("operator", PASSWORD).status()).as("still below the limit").isEqualTo(200);
	}

	@Test
	void sixthFailureIsThrottledWithRetryAfterEvenWithTheRightPasswordAndTheLockExpires() {
		client.get("/api/v1/auth/session");
		for (int i = 0; i < 5; i++) {
			assertThat(client.login("operator", "wrong-" + i).status()).as("failure " + (i + 1)).isEqualTo(401);
		}
		AuthTestClient.Reply sixth = client.login("operator", "wrong-6");
		assertThat(sixth.status()).isEqualTo(429);
		assertThat(Long.parseLong(sixth.header("Retry-After"))).isBetween(1L, 900L);
		assertThat(JsonPath.<Integer>read(sixth.body(), "$.retryAfterSeconds")).isBetween(1, 900);

		AuthTestClient.Reply correctDuringLock = client.login("operator", PASSWORD);
		assertThat(correctDuringLock.status()).as("the right password does not unlock early").isEqualTo(429);
		assertThat(correctDuringLock.setCookie("REPOINTEL_SESSION")).isNull();
		assertThat(client.get(PROTECTED).status()).isEqualTo(401);

		clock.advance(Duration.ofMinutes(14));
		assertThat(client.login("operator", PASSWORD).status()).as("still locked after 14 minutes").isEqualTo(429);
		clock.advance(Duration.ofMinutes(2));
		assertThat(client.login("operator", PASSWORD).status()).as("lock expired").isEqualTo(200);
	}

	@Test
	void csrfTokenIsRequiredOnLoginAndOnEveryStateChangingCall() {
		client.get("/api/v1/auth/session");
		assertThat(client.postWithoutCsrf("/api/v1/auth/login",
				"{\"username\":\"operator\",\"password\":\"" + PASSWORD + "\"}").status())
			.as("login without the header")
			.isEqualTo(403);
		assertThat(client.post("/api/v1/auth/login", "{\"username\":\"operator\",\"password\":\"" + PASSWORD + "\"}")
			.status()).isEqualTo(200);

		String cancel = "/api/v1/analysis-runs/" + UUID.randomUUID() + "/cancel";
		AuthTestClient.Reply withoutHeader = client.postWithoutCsrf(cancel, null);
		assertThat(withoutHeader.status()).isEqualTo(403);
		assertThat(JsonPath.<String>read(withoutHeader.body(), "$.detail")).containsIgnoringCase("csrf");
		assertThat(client.postWithoutCsrf(cancel, null, "X-XSRF-TOKEN", "forged-value").status()).isEqualTo(403);
		assertThat(client.post(cancel, null).status()).as("with the header the call reaches the controller")
			.isEqualTo(404);
		assertThat(client.get(PROTECTED).status()).as("GET needs no token").isEqualTo(200);
	}

	@Test
	void logoutInvalidatesTheSessionOnTheServerAndClearsTheCookies() {
		client.get("/api/v1/auth/session");
		client.login("operator", PASSWORD);
		String session = client.cookie("REPOINTEL_SESSION");

		AuthTestClient.Reply logout = client.post("/api/v1/auth/logout", null);
		assertThat(logout.status()).isEqualTo(204);
		assertThat(logout.header("Cache-Control")).isEqualTo("no-store");
		assertThat(logout.setCookie("REPOINTEL_SESSION")).contains("Max-Age=0");
		assertThat(client.cookie("REPOINTEL_SESSION")).isNull();

		AuthTestClient replay = new AuthTestClient(port);
		replay.setCookie("REPOINTEL_SESSION", session);
		assertThat(replay.get(PROTECTED).status()).as("the old cookie replayed after logout").isEqualTo(401);
	}

	@Test
	void logoutRequiresTheCsrfTokenAndIsHarmlessWithoutASession() {
		client.get("/api/v1/auth/session");
		assertThat(client.postWithoutCsrf("/api/v1/auth/logout", null).status()).isEqualTo(403);
		assertThat(client.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
	}

	@Test
	void idleTimeoutEndsTheSessionButActivityRenewsIt() {
		client.get("/api/v1/auth/session");
		client.login("operator", PASSWORD);

		clock.advance(Duration.ofHours(1).plusMinutes(59));
		assertThat(client.get(PROTECTED).status()).as("just inside the idle limit").isEqualTo(200);
		clock.advance(Duration.ofHours(1).plusMinutes(59));
		assertThat(client.get(PROTECTED).status()).as("the previous request renewed it").isEqualTo(200);
		clock.advance(Duration.ofHours(2).plusSeconds(1));
		assertThat(client.get(PROTECTED).status()).as("idle for more than two hours").isEqualTo(401);
		assertThat(client.get(PROTECTED).status()).as("and the session is gone for good").isEqualTo(401);
	}

	@Test
	void absoluteLifetimeEndsTheSessionEvenWhileItIsBeingUsed() {
		client.get("/api/v1/auth/session");
		client.login("operator", PASSWORD);

		for (int hour = 1; hour <= 11; hour++) {
			clock.advance(Duration.ofHours(1));
			assertThat(client.get(PROTECTED).status()).as("hour " + hour).isEqualTo(200);
		}
		clock.advance(Duration.ofHours(1));
		assertThat(client.get(PROTECTED).status()).as("12 hours after login, however active").isEqualTo(401);
	}

	@Test
	void passwordsSessionIdsAndHashesNeverReachTheLogs(CapturedOutput output) {
		client.get("/api/v1/auth/session");
		String planted = "ABCDEF0123456789ABCDEF0123456789";
		client.setCookie("REPOINTEL_SESSION", planted);
		client.login("operator", "a-planted-wrong-password-7391");
		client.login("operator", PASSWORD);
		String realSession = client.cookie("REPOINTEL_SESSION");
		String csrf = client.cookie("XSRF-TOKEN");
		client.get(PROTECTED);
		client.post("/api/v1/auth/logout", null);

		String logs = output.getAll();
		assertThat(logs).doesNotContain("a-planted-wrong-password-7391").doesNotContain(PASSWORD)
			.doesNotContain(planted)
			.doesNotContain(realSession)
			.doesNotContain(csrf)
			.doesNotContain("$2a$04$")
			.doesNotContain("$2b$04$");
	}

	/** The request id and timestamp differ per call; everything else must be identical. */
	private static String stripVolatile(String body) {
		return body.replaceAll("\"(timestamp|requestId)\":\"[^\"]*\"", "").replaceAll("\"instance\":\"[^\"]*\"", "");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
		}

		/** Creates a session before login when asked, to prove the login does not keep that session id. */
		@Bean
		FilterRegistrationBean<OncePerRequestFilter> sessionPlanter() {
			FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
					new OncePerRequestFilter() {
						@Override
						protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
								FilterChain chain) throws ServletException, IOException {
							if (request.getHeader("X-Test-Plant-Session") != null) {
								request.getSession(true);
							}
							chain.doFilter(request, response);
						}
					});
			registration.setOrder(Integer.MIN_VALUE + 10);
			return registration;
		}

	}

}
