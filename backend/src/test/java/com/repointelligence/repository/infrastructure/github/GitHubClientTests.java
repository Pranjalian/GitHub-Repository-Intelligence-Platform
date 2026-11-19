package com.repointelligence.repository.infrastructure.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.repointelligence.common.exception.AnalysisLimitException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.exception.UpstreamRateLimitException;
import com.repointelligence.common.exception.UpstreamUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The GitHub client against a WireMock stand-in: no real GitHub calls. */
class GitHubClientTests {

	private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

	private static final String TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";

	private static final String REPO_JSON = """
			{"id": 1296269, "name": "Hello-World", "owner": {"login": "octocat"}, "default_branch": "main",
			 "visibility": "public", "private": false, "size": 108, "archived": false, "disabled": false,
			 "stargazers_count": 80}
			""";

	private WireMockServer server;

	private final List<Duration> sleeps = new ArrayList<>();

	@TempDir
	Path tempDir;

	@BeforeEach
	void startServer() {
		server = new WireMockServer(options().dynamicPort());
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop();
	}

	// ---- metadata ------------------------------------------------------------------------------

	@Test
	void getRepositoryMapsMetadataAndSendsStandardHeaders() {
		server.stubFor(get(urlEqualTo("/repos/octocat/Hello-World")).willReturn(json(REPO_JSON)));

		GitHubRepositoryInfo info = client(TOKEN).getRepository("octocat", "Hello-World");

		assertThat(info).isEqualTo(new GitHubRepositoryInfo(1296269L, "octocat", "Hello-World", "main", "public", 108L,
				false, false));
		server.verify(getRequestedFor(urlEqualTo("/repos/octocat/Hello-World"))
			.withHeader("Accept", equalTo("application/vnd.github+json"))
			.withHeader("X-GitHub-Api-Version", equalTo("2022-11-28"))
			.withHeader("Authorization", equalTo("Bearer " + TOKEN)));
	}

	@Test
	void noAuthorizationHeaderWithoutAToken() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(json(REPO_JSON)));

		client("").getRepository("o", "r");

		server.verify(getRequestedFor(urlEqualTo("/repos/o/r")).withoutHeader("Authorization"));
	}

	@Test
	void reportsArchivedAndPrivateFlags() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(json("""
				{"id": 2, "name": "r", "owner": {"login": "o"}, "default_branch": "dev", "private": true,
				 "size": 5, "archived": true, "disabled": true}
				""")));

		GitHubRepositoryInfo info = client("").getRepository("o", "r");

		assertThat(info.visibility()).isEqualTo("private");
		assertThat(info.archived()).isTrue();
		assertThat(info.disabled()).isTrue();
	}

	@Test
	void followsRepositoryRenameRedirectOnTheApiHostAndKeepsTheToken() {
		server.stubFor(get(urlEqualTo("/repos/old/name")).willReturn(
				aResponse().withStatus(301).withHeader("Location", "http://localhost:" + server.port() + "/repositories/1296269")));
		server.stubFor(get(urlEqualTo("/repositories/1296269")).willReturn(json(REPO_JSON)));

		GitHubRepositoryInfo info = client(TOKEN).getRepository("old", "name");

		assertThat(info.name()).isEqualTo("Hello-World");
		server.verify(getRequestedFor(urlEqualTo("/repositories/1296269")).withHeader("Authorization",
				equalTo("Bearer " + TOKEN)));
	}

	@Test
	void unknownRepositoryIsNotFound() {
		server.stubFor(get(urlEqualTo("/repos/o/missing")).willReturn(aResponse().withStatus(404)));

		assertThatThrownBy(() -> client("").getRepository("o", "missing")).isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("Repository not found or not public.");
		assertThat(server.getAllServeEvents()).hasSize(1);
	}

	// ---- commit resolution ---------------------------------------------------------------------

	@Test
	void resolvesBranchToExactSha() {
		server.stubFor(get(urlEqualTo("/repos/o/r/commits/main")).willReturn(json("{\"sha\": \"" + SHA + "\"}")));

		assertThat(client("").resolveCommitSha("o", "r", "main")).isEqualTo(SHA);
	}

	@Test
	void resolvesBranchNamesContainingSlashes() {
		server.stubFor(get(urlEqualTo("/repos/o/r/commits/feature/login-page"))
			.willReturn(json("{\"sha\": \"" + SHA + "\"}")));

		assertThat(client("").resolveCommitSha("o", "r", "feature/login-page")).isEqualTo(SHA);
	}

	@Test
	void unknownRefIsNotFound() {
		server.stubFor(get(urlEqualTo("/repos/o/r/commits/nope")).willReturn(aResponse().withStatus(422)));

		assertThatThrownBy(() -> client("").resolveCommitSha("o", "r", "nope")).isInstanceOf(ResourceNotFoundException.class)
			.hasMessage("Repository, branch or commit not found.");
	}

	@Test
	void malformedShaInResponseIsRejected() {
		server.stubFor(get(urlEqualTo("/repos/o/r/commits/main")).willReturn(json("{\"sha\": \"not-a-sha\"}")));

		assertThatThrownBy(() -> client("").resolveCommitSha("o", "r", "main"))
			.isInstanceOf(UpstreamUnavailableException.class);
	}

	// ---- tarball -------------------------------------------------------------------------------

	@Test
	void downloadsTarballThroughTheAllowlistedRedirectWithoutLeakingTheToken() throws IOException {
		byte[] archive = gzip("fixture archive content");
		// The API is "localhost"; the download host (like codeload.github.com) is a different allowlisted host.
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(302)
			.withHeader("Location", "http://127.0.0.1:" + server.port() + "/codeload/o/r/legacy.tar.gz/" + SHA)));
		server.stubFor(get(urlPathEqualTo("/codeload/o/r/legacy.tar.gz/" + SHA))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(archive)));
		Path target = tempDir.resolve("nested/dir/archive.tar.gz");

		clientAllowing(TOKEN, List.of("localhost", "127.0.0.1")).downloadTarball("o", "r", SHA, target, 1_000_000);

		assertThat(Files.readAllBytes(target)).isEqualTo(archive);
		server.verify(getRequestedFor(urlEqualTo("/repos/o/r/tarball/" + SHA)).withHeader("Authorization",
				equalTo("Bearer " + TOKEN)));
		server.verify(getRequestedFor(urlPathEqualTo("/codeload/o/r/legacy.tar.gz/" + SHA)).withoutHeader("Authorization"));
	}

	@Test
	void redirectToANonAllowlistedHostIsRefusedAndNeverContacted() {
		// 127.0.0.1 reaches the same WireMock server but is not on the allowlist (only "localhost" is).
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(302)
			.withHeader("Location", "http://127.0.0.1:" + server.port() + "/evil/archive")));
		server.stubFor(get(urlPathEqualTo("/evil/archive")).willReturn(aResponse().withStatus(200).withBody("x")));

		assertThatThrownBy(() -> client(TOKEN).downloadTarball("o", "r", SHA, tempDir.resolve("a.tgz"), 1000))
			.isInstanceOf(UpstreamUnavailableException.class)
			.hasMessage("GitHub redirected to a host that is not allowed.");
		server.verify(0, getRequestedFor(urlPathEqualTo("/evil/archive")));
		assertThat(tempDir.resolve("a.tgz")).doesNotExist();
	}

	@Test
	void redirectToAnExternalHostIsRefused() {
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(
				aResponse().withStatus(302).withHeader("Location", "https://evil.example.com/steal")));

		assertThatThrownBy(() -> client(TOKEN).downloadTarball("o", "r", SHA, tempDir.resolve("a.tgz"), 1000))
			.isInstanceOf(UpstreamUnavailableException.class)
			.hasMessage("GitHub redirected to a host that is not allowed.");
	}

	@Test
	void plainHttpRedirectIsRefusedWhenHttpsIsRequired() {
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(302)
			.withHeader("Location", "http://localhost:" + server.port() + "/codeload/x")));
		GitHubClient strict = client(TOKEN, true, 3, 5);

		assertThatThrownBy(() -> strict.downloadTarball("o", "r", SHA, tempDir.resolve("a.tgz"), 1000))
			.isInstanceOf(UpstreamUnavailableException.class)
			.hasMessage("GitHub redirected to a host that is not allowed.");
	}

	@Test
	void redirectLoopsAreBounded() {
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(302)
			.withHeader("Location", "http://localhost:" + server.port() + "/repos/o/r/tarball/" + SHA)));

		assertThatThrownBy(() -> client("").downloadTarball("o", "r", SHA, tempDir.resolve("a.tgz"), 1000))
			.isInstanceOf(UpstreamUnavailableException.class)
			.hasMessage("GitHub redirected too many times.");
		assertThat(server.getAllServeEvents()).hasSize(4);
	}

	@Test
	void tarballLargerThanTheLimitIsRejectedByContentLengthAndNothingIsKept() {
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200).withBody(new byte[5000])));
		Path target = tempDir.resolve("big.tgz");

		assertThatThrownBy(() -> client("").downloadTarball("o", "r", SHA, target, 1000))
			.isInstanceOf(AnalysisLimitException.class)
			.hasMessageContaining("exceeds the maximum allowed size");
		assertThat(target).doesNotExist();
	}

	@Test
	void streamingStopsAtTheByteLimitWhenNoContentLengthIsAvailable() {
		server.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200).withBody(new byte[200_000]).withChunkedDribbleDelay(10, 10)));
		Path target = tempDir.resolve("chunked.tgz");

		assertThatThrownBy(() -> client("").downloadTarball("o", "r", SHA, target, 50_000))
			.isInstanceOf(AnalysisLimitException.class);
		assertThat(target).doesNotExist();
	}

	@Test
	void copyLimitedAllowsExactlyTheLimitAndRejectsOneByteMore() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		assertThat(GitHubClient.copyLimited(new ByteArrayInputStream(new byte[100]), out, 100)).isEqualTo(100);
		assertThat(out.size()).isEqualTo(100);
		assertThatThrownBy(() -> GitHubClient.copyLimited(new ByteArrayInputStream(new byte[101]),
				new ByteArrayOutputStream(), 100))
			.isInstanceOf(AnalysisLimitException.class);
	}

	@Test
	void downloadRejectsAnythingButAFullSha() {
		assertThatThrownBy(() -> client("").downloadTarball("o", "r", "main", tempDir.resolve("a.tgz"), 1000))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client("").downloadTarball("o", "r", "../../etc/passwd", tempDir.resolve("a.tgz"), 1000))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(server.getAllServeEvents()).isEmpty();
	}

	// ---- rate limits and retries ---------------------------------------------------------------

	@Test
	void primaryRateLimitFailsFastWithTheResetTimeAndNeverSleeps() {
		long reset = Instant.now().plusSeconds(1800).getEpochSecond();
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(aResponse().withStatus(403)
			.withHeader("X-RateLimit-Remaining", "0")
			.withHeader("X-RateLimit-Reset", String.valueOf(reset))));

		assertThatThrownBy(() -> client("").getRepository("o", "r")).isInstanceOfSatisfying(
				UpstreamRateLimitException.class, ex -> {
					assertThat(ex.getResetAt()).isEqualTo(Instant.ofEpochSecond(reset));
					assertThat(ex.getMessage()).contains("GITHUB_TOKEN");
				});
		assertThat(sleeps).isEmpty();
		assertThat(server.getAllServeEvents()).hasSize(1);
	}

	@Test
	void primaryRateLimitAlsoAppliesTo429() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(aResponse().withStatus(429)
			.withHeader("X-RateLimit-Remaining", "0")));

		assertThatThrownBy(() -> client("").getRepository("o", "r")).isInstanceOf(UpstreamRateLimitException.class);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void shortSecondaryLimitIsRetriedAfterTheRequestedDelay() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).inScenario("secondary")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(403).withHeader("Retry-After", "2"))
			.willSetStateTo("ok"));
		server.stubFor(get(urlEqualTo("/repos/o/r")).inScenario("secondary")
			.whenScenarioStateIs("ok")
			.willReturn(json(REPO_JSON)));

		GitHubRepositoryInfo info = client("").getRepository("o", "r");

		assertThat(info.id()).isEqualTo(1296269L);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
	}

	@Test
	void longSecondaryLimitFailsFastInsteadOfParkingAThread() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(aResponse().withStatus(403).withHeader("Retry-After", "3600")));

		assertThatThrownBy(() -> client("").getRepository("o", "r")).isInstanceOf(UpstreamRateLimitException.class);
		assertThat(sleeps).isEmpty();
		assertThat(server.getAllServeEvents()).hasSize(1);
	}

	@Test
	void serverErrorsAreRetriedWithBoundedExponentialBackoffThenFail() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(aResponse().withStatus(503)));

		assertThatThrownBy(() -> client("").getRepository("o", "r")).isInstanceOf(UpstreamUnavailableException.class)
			.hasMessageContaining("HTTP 503");
		assertThat(server.getAllServeEvents()).hasSize(3);
		assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
	}

	@Test
	void transientServerErrorThenSuccessSucceeds() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).inScenario("flaky")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(aResponse().withStatus(502))
			.willSetStateTo("second"));
		server.stubFor(get(urlEqualTo("/repos/o/r")).inScenario("flaky")
			.whenScenarioStateIs("second")
			.willReturn(aResponse().withStatus(500))
			.willSetStateTo("third"));
		server.stubFor(get(urlEqualTo("/repos/o/r")).inScenario("flaky")
			.whenScenarioStateIs("third")
			.willReturn(json(REPO_JSON)));

		assertThat(client("").getRepository("o", "r").name()).isEqualTo("Hello-World");
		assertThat(sleeps).hasSize(2);
	}

	@Test
	void timeoutsAreRetriedThenReportedAsUnreachable() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(json(REPO_JSON).withFixedDelay(1500)));
		GitHubClient slow = new GitHubClient(properties("", false, 3, 5, Duration.ofMillis(300)),
				new GitHubRateLimitPolicy(properties("", false, 3, 5, Duration.ofMillis(300))), sleeps::add);

		assertThatThrownBy(() -> slow.getRepository("o", "r")).isInstanceOf(UpstreamUnavailableException.class)
			.hasMessageStartingWith("Cannot reach GitHub");
		assertThat(sleeps).hasSize(2);
	}

	@Test
	void unauthorizedTokenIsReportedWithoutEchoingIt() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(aResponse().withStatus(401)));

		assertThatThrownBy(() -> client(TOKEN).getRepository("o", "r")).isInstanceOf(UpstreamUnavailableException.class)
			.hasMessageContaining("rejected the configured token")
			.satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(TOKEN));
		assertThat(server.getAllServeEvents()).hasSize(1);
	}

	@Test
	void forbiddenWithoutRateLimitHintIsARefusalAndNotRetried() {
		server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(aResponse().withStatus(403)));

		assertThatThrownBy(() -> client("").getRepository("o", "r")).isInstanceOf(UpstreamUnavailableException.class)
			.hasMessageContaining("HTTP 403");
		assertThat(server.getAllServeEvents()).hasSize(1);
		assertThat(sleeps).isEmpty();
	}

	// ---- secrets -------------------------------------------------------------------------------

	@Test
	void theTokenNeverAppearsInLogs() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		Level previous = root.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		root.setLevel(Level.TRACE);
		try {
			server.stubFor(get(urlEqualTo("/repos/o/r")).willReturn(json(REPO_JSON)));
			server.stubFor(get(urlEqualTo("/repos/o/flaky")).willReturn(aResponse().withStatus(503)));
			server.stubFor(get(urlEqualTo("/repos/o/denied")).willReturn(aResponse().withStatus(401)));
			GitHubClient client = client(TOKEN);

			client.getRepository("o", "r");
			assertThatThrownBy(() -> client.getRepository("o", "flaky")).isInstanceOf(UpstreamUnavailableException.class);
			assertThatThrownBy(() -> client.getRepository("o", "denied")).isInstanceOf(UpstreamUnavailableException.class);
		}
		finally {
			root.detachAppender(appender);
			root.setLevel(previous);
		}

		assertThat(appender.list).isNotEmpty();
		assertThat(appender.list).noneSatisfy(event -> {
			assertThat(event.getFormattedMessage()).contains("ghp_");
			assertThat(event.getFormattedMessage()).doesNotContain("Bearer");
		});
	}

	@Test
	void propertiesToStringHidesTheToken() {
		assertThat(properties(TOKEN, false, 3, 5, Duration.ofSeconds(5)).toString()).doesNotContain(TOKEN)
			.contains("token=***");
		assertThat(properties("", false, 3, 5, Duration.ofSeconds(5)).toString()).contains("token=<none>");
	}

	@Test
	void apiHostMustBeOnTheAllowlist() {
		GitHubProperties props = new GitHubProperties("http://localhost:1", "", "2022-11-28", Duration.ofSeconds(1),
				Duration.ofSeconds(1), List.of("api.github.com"), true, 3,
				new GitHubProperties.Retry(3, Duration.ofMillis(500), Duration.ofSeconds(5)));

		assertThatThrownBy(() -> new GitHubClient(props, new GitHubRateLimitPolicy(props), sleeps::add))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("allowed-hosts");
	}

	@Test
	void backoffDoublesAndIsCapped() {
		GitHubRateLimitPolicy policy = new GitHubRateLimitPolicy(properties("", false, 3, 5, Duration.ofSeconds(5)));

		assertThat(List.of(1, 2, 3, 4, 5, 6, 30).stream().map(policy::backoff).toList()).containsExactly(
				Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofMillis(2000), Duration.ofMillis(4000),
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
	}

	// ---- helpers -------------------------------------------------------------------------------

	private GitHubClient client(String token) {
		return client(token, false, 3, 5);
	}

	private GitHubClient clientAllowing(String token, List<String> allowedHosts) {
		GitHubProperties base = properties(token, false, 3, 5, Duration.ofSeconds(5));
		GitHubProperties props = new GitHubProperties(base.apiBaseUrl(), token, base.apiVersion(), base.connectTimeout(),
				base.readTimeout(), allowedHosts, false, 3, base.retry());
		return new GitHubClient(props, new GitHubRateLimitPolicy(props), sleeps::add);
	}

	private GitHubClient client(String token, boolean requireHttps, int maxRedirects, int maxBackoffSeconds) {
		GitHubProperties props = properties(token, requireHttps, maxRedirects, maxBackoffSeconds, Duration.ofSeconds(5));
		return new GitHubClient(props, new GitHubRateLimitPolicy(props), sleeps::add);
	}

	private GitHubProperties properties(String token, boolean requireHttps, int maxRedirects, int maxBackoffSeconds,
			Duration readTimeout) {
		return new GitHubProperties("http://localhost:" + server.port(), token, "2022-11-28", Duration.ofSeconds(2),
				readTimeout, List.of("localhost"), requireHttps, maxRedirects,
				new GitHubProperties.Retry(3, Duration.ofMillis(500), Duration.ofSeconds(maxBackoffSeconds)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static byte[] gzip(String content) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
			gzip.write(content.getBytes(StandardCharsets.UTF_8));
		}
		return bytes.toByteArray();
	}

}
