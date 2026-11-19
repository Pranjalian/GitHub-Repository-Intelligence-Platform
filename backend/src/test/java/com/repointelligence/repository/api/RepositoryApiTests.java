package com.repointelligence.repository.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.repointelligence.analysis.domain.AnalysisRequestedEvent;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The submission and read APIs end to end: HTTP, service, PostgreSQL and a WireMock stand-in for
 * GitHub (no real GitHub calls).
 */
@Import({ EmbeddedPostgresConfig.class, RepositoryApiTests.TestClockConfig.class })
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class RepositoryApiTests {

	private static final String SHA_1 = "1".repeat(40);

	private static final String SHA_2 = "2".repeat(40);

	private static final String URL = "https://github.com/octocat/Hello-World";

	private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

	static {
		GITHUB.start();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestClockConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
		}

	}

	@DynamicPropertySource
	static void githubStandIn(DynamicPropertyRegistry registry) {
		registry.add("github.api-base-url", () -> "http://localhost:" + GITHUB.port());
		registry.add("github.allowed-hosts", () -> "localhost");
		registry.add("github.require-https", () -> "false");
		// These tests assert on queued runs; the pipeline itself is tested in AnalysisOrchestratorTests.
		registry.add("analysis.jobs.enabled", () -> "false");
	}

	@AfterAll
	static void stopGithub() {
		GITHUB.stop();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MutableClock clock;

	@Autowired
	private ApplicationEvents events;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM repository");
		GITHUB.resetAll();
		clock.advance(Duration.ofHours(2));
		stubRepository("octocat", "Hello-World", repoJson("octocat", "Hello-World", "public", 108, false, false));
		stubCommit("octocat", "Hello-World", "main", SHA_1);
	}

	/** These tests commit real rows; remove them so other test classes see an empty database. */
	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	// ---- POST /analyze: new runs ---------------------------------------------------------------

	@Test
	void validUrlResolvesTheCommitAndQueuesARun() throws Exception {
		submit(URL, false).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.commitSha").value(SHA_1))
			.andExpect(jsonPath("$.branch").value("main"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.reused").value(false))
			.andExpect(jsonPath("$.repositoryId").isNotEmpty())
			.andExpect(jsonPath("$.snapshotId").isNotEmpty())
			.andExpect(jsonPath("$.analysisRunId").isNotEmpty())
			.andExpect(jsonPath("$.warnings", hasSize(0)));

		assertThat(count("repository")).isEqualTo(1);
		assertThat(count("repository_snapshot")).isEqualTo(1);
		assertThat(count("analysis_run")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT github_repo_id FROM repository", Long.class)).isEqualTo(1296269L);
	}

	@Test
	void forceRescanDefaultsToFalseWhenOmitted() throws Exception {
		mockMvc
			.perform(post("/api/v1/repositories/analyze").contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"" + URL + "\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("QUEUED"));

		assertThat(jdbc.queryForObject("SELECT force_rescan FROM analysis_run", Boolean.class)).isFalse();
	}

	@Test
	void queuedRunPublishesAnEventForTheAsyncPipeline() throws Exception {
		submit(URL, false).andExpect(status().isAccepted());

		UUID runId = jdbc.queryForObject("SELECT id FROM analysis_run", UUID.class);
		assertThat(events.stream(AnalysisRequestedEvent.class)).extracting(AnalysisRequestedEvent::runId)
			.containsExactly(runId);
	}

	@Test
	void treeUrlAnalyzesTheRequestedBranch() throws Exception {
		stubCommit("octocat", "Hello-World", "dev", SHA_2);

		submit(URL + "/tree/dev", false).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.branch").value("dev"))
			.andExpect(jsonPath("$.commitSha").value(SHA_2));
	}

	@Test
	void repositoryIdentityIsCaseInsensitiveAndAdoptsGitHubsCasing() throws Exception {
		stubRepository("octocat", "hello-world", repoJson("octocat", "Hello-World", "public", 108, false, false));
		stubCommit("octocat", "hello-world", "main", SHA_1);
		submit(URL, false).andExpect(status().isAccepted());

		submit("https://github.com/octocat/hello-world", false).andExpect(status().isOk())
			.andExpect(jsonPath("$.reused").value(true));

		assertThat(count("repository")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT name FROM repository", String.class)).isEqualTo("Hello-World");
	}

	// ---- POST /analyze: rejections -------------------------------------------------------------

	@Test
	void invalidUrlIsRejectedWithoutCallingGitHub() throws Exception {
		submit("https://gitlab.com/o/r", false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.requestId").exists());

		assertThat(GITHUB.getAllServeEvents()).isEmpty();
		assertThat(count("repository")).isZero();
	}

	@Test
	void blankAndOverlongUrlsFailValidation() throws Exception {
		submit("", false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0]").exists());
		submit("https://github.com/o/" + "r".repeat(600), false).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/repositories/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void unknownRepositoryIsNotFound() throws Exception {
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/missing")).willReturn(aResponse().withStatus(404)));

		submit("https://github.com/octocat/missing", false).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Repository not found or not public."));
		assertThat(count("repository")).isZero();
	}

	@Test
	void privateRepositoriesAreRefusedEvenIfTheServerTokenCanSeeThem() throws Exception {
		stubRepository("octocat", "secret", repoJson("octocat", "secret", "private", 10, false, false));

		submit("https://github.com/octocat/secret", false).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Repository not found or not public."));
		assertThat(count("repository")).isZero();
	}

	@Test
	void repositoryOverTheSizeLimitIsRejected() throws Exception {
		stubRepository("octocat", "huge", repoJson("octocat", "huge", "public", 300 * 1024, false, false));

		submit("https://github.com/octocat/huge", false).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.detail").value("Repository is about 300 MB, which exceeds the limit of 200 MB."));
		assertThat(count("repository")).isZero();
	}

	@Test
	void disabledRepositoryIsRejectedAndArchivedOnesCarryAWarning() throws Exception {
		stubRepository("octocat", "off", repoJson("octocat", "off", "public", 10, false, true));
		stubRepository("octocat", "old", repoJson("octocat", "old", "public", 10, true, false));
		stubCommit("octocat", "old", "main", SHA_1);

		submit("https://github.com/octocat/off", false).andExpect(status().isUnprocessableEntity());
		submit("https://github.com/octocat/old", false).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("archived")));
	}

	@Test
	void unknownBranchIsNotFound() throws Exception {
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/Hello-World/commits/nope")).willReturn(aResponse().withStatus(422)));

		submit(URL + "/tree/nope", false).andExpect(status().isNotFound());
		assertThat(count("repository")).isZero();
	}

	// ---- reuse and rescan ----------------------------------------------------------------------

	@Test
	void resubmittingWhileARunIsQueuedReturnsThatRun() throws Exception {
		String runId = submitAndGet(URL, false, "$.analysisRunId");

		submit(URL, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.reused").value(true))
			.andExpect(jsonPath("$.analysisRunId").value(runId))
			.andExpect(jsonPath("$.status").value("QUEUED"));

		assertThat(count("analysis_run")).isEqualTo(1);
	}

	@Test
	void forcedRescanWhileARunIsInFlightStillReturnsThatRun() throws Exception {
		String runId = submitAndGet(URL, false, "$.analysisRunId");

		submit(URL, true).andExpect(status().isOk()).andExpect(jsonPath("$.analysisRunId").value(runId));

		assertThat(count("analysis_run")).isEqualTo(1);
	}

	@Test
	void completedRunWithTheCurrentAnalyzerSetIsReused() throws Exception {
		String runId = submitAndGet(URL, false, "$.analysisRunId");
		jdbc.update("UPDATE analysis_run SET status = 'COMPLETED_WITH_WARNINGS'");

		submit(URL, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.reused").value(true))
			.andExpect(jsonPath("$.analysisRunId").value(runId))
			.andExpect(jsonPath("$.status").value("COMPLETED_WITH_WARNINGS"));
		assertThat(count("analysis_run")).isEqualTo(1);
	}

	@Test
	void completedRunFromADifferentAnalyzerSetIsNotReused() throws Exception {
		String runId = submitAndGet(URL, false, "$.analysisRunId");
		jdbc.update("UPDATE analysis_run SET status = 'COMPLETED', analyzer_set_hash = 'older-analyzers'");

		submit(URL, false).andExpect(status().isAccepted()).andExpect(jsonPath("$.reused").value(false));

		assertThat(count("analysis_run")).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_run WHERE id = ?::uuid", Integer.class, runId))
			.isEqualTo(1);
	}

	@Test
	void forceRescanCreatesANewRunOnTheSameSnapshot() throws Exception {
		String snapshotId = submitAndGet(URL, false, "$.snapshotId");
		String firstRun = jdbc.queryForObject("SELECT id::text FROM analysis_run", String.class);
		jdbc.update("UPDATE analysis_run SET status = 'COMPLETED'");

		submit(URL, true).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.reused").value(false))
			.andExpect(jsonPath("$.snapshotId").value(snapshotId))
			.andExpect(jsonPath("$.analysisRunId").value(org.hamcrest.Matchers.not(firstRun)));

		assertThat(count("repository_snapshot")).isEqualTo(1);
		assertThat(count("analysis_run")).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT force_rescan FROM analysis_run WHERE status = 'QUEUED'", Boolean.class))
			.isTrue();
	}

	@Test
	void newUpstreamCommitCreatesANewSnapshotOnTheSameRepository() throws Exception {
		String repositoryId = submitAndGet(URL, false, "$.repositoryId");
		stubCommit("octocat", "Hello-World", "main", SHA_2);

		submit(URL, false).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.repositoryId").value(repositoryId))
			.andExpect(jsonPath("$.commitSha").value(SHA_2));

		assertThat(count("repository")).isEqualTo(1);
		assertThat(count("repository_snapshot")).isEqualTo(2);
	}

	// ---- protection ----------------------------------------------------------------------------

	@Test
	void submissionsAreRateLimitedAndRecover() throws Exception {
		for (int i = 0; i < 10; i++) {
			submit("https://gitlab.com/o/r", false).andExpect(status().isBadRequest());
		}

		submit("https://gitlab.com/o/r", false).andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.retryAfterSeconds").isNumber());

		clock.advance(Duration.ofMinutes(1));
		submit(URL, false).andExpect(status().isAccepted());
	}

	@Test
	void fullQueueRejectsNewRunsButStillReusesExistingOnes() throws Exception {
		String repositoryId = submitAndGet(URL, false, "$.repositoryId");
		UUID snapshotId = jdbc.queryForObject("SELECT id FROM repository_snapshot", UUID.class);
		for (int i = 0; i < 9; i++) {
			jdbc.update("INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'ANALYZING')", snapshotId);
		}
		stubRepository("octocat", "other", repoJson("octocat", "other", "public", 10, false, false));
		stubCommit("octocat", "other", "main", SHA_2);

		submit("https://github.com/octocat/other", false).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.detail").value("The analysis queue is full. Try again later."));
		submit(URL, false).andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoryId").value(repositoryId))
			.andExpect(jsonPath("$.reused").value(true));

		assertThat(count("repository")).isEqualTo(1);
	}

	// ---- reads ---------------------------------------------------------------------------------

	@Test
	void listIsEmptyThenShowsTheSubmittedRepositoryWithItsLatestRun() throws Exception {
		mockMvc.perform(get("/api/v1/repositories")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalElements").value(0));

		submit(URL, false).andExpect(status().isAccepted());

		mockMvc.perform(get("/api/v1/repositories")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].owner").value("octocat"))
			.andExpect(jsonPath("$.items[0].name").value("Hello-World"))
			.andExpect(jsonPath("$.items[0].canonicalUrl").value(URL))
			.andExpect(jsonPath("$.items[0].latestSnapshot.commitSha").value(SHA_1))
			.andExpect(jsonPath("$.items[0].latestSnapshot.latestRun.status").value("QUEUED"));
	}

	@Test
	void repositoryDetailIncludesLatestSnapshotAndRun() throws Exception {
		String repositoryId = submitAndGet(URL, false, "$.repositoryId");

		mockMvc.perform(get("/api/v1/repositories/" + repositoryId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(repositoryId))
			.andExpect(jsonPath("$.defaultBranch").value("main"))
			.andExpect(jsonPath("$.visibility").value("public"))
			.andExpect(jsonPath("$.latestSnapshot.branch").value("main"))
			.andExpect(jsonPath("$.latestSnapshot.ingestionStatus").value("PENDING"))
			.andExpect(jsonPath("$.latestSnapshot.latestRun.status").value("QUEUED"));
	}

	@Test
	void unknownRepositoryAndMalformedIdsAreHandled() throws Exception {
		mockMvc.perform(get("/api/v1/repositories/" + UUID.randomUUID())).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/snapshots")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/repositories/not-a-uuid")).andExpect(status().isBadRequest());
	}

	@Test
	void snapshotsAreListedNewestFirstWithPagingAndTheirOwnRunStatus() throws Exception {
		String repositoryId = submitAndGet(URL, false, "$.repositoryId");
		jdbc.update("UPDATE analysis_run SET status = 'COMPLETED'");
		Thread.sleep(5);
		stubCommit("octocat", "Hello-World", "main", SHA_2);
		submit(URL, false).andExpect(status().isAccepted());
		// A second repository must never leak into this repository's snapshot list.
		stubRepository("octocat", "other", repoJson("octocat", "other", "public", 10, false, false));
		stubCommit("octocat", "other", "main", SHA_1);
		submit("https://github.com/octocat/other", false).andExpect(status().isAccepted());

		mockMvc.perform(get("/api/v1/repositories/" + repositoryId + "/snapshots")).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.items[0].commitSha").value(SHA_2))
			.andExpect(jsonPath("$.items[0].latestRun.status").value("QUEUED"))
			.andExpect(jsonPath("$.items[1].commitSha").value(SHA_1))
			.andExpect(jsonPath("$.items[1].latestRun.status").value("COMPLETED"));
		mockMvc.perform(get("/api/v1/repositories/" + repositoryId + "/snapshots?page=1&size=1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].commitSha").value(SHA_1))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void pagingParametersAreValidated() throws Exception {
		mockMvc.perform(get("/api/v1/repositories?size=0")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/repositories?size=101")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/repositories?page=-1")).andExpect(status().isBadRequest());
	}

	@Test
	void everyGitHubCallUsesTheResolvedRefAndNoUserSuppliedUrl() throws Exception {
		submit(URL, false).andExpect(status().isAccepted());

		GITHUB.verify(1, getRequestedFor(urlEqualTo("/repos/octocat/Hello-World")));
		GITHUB.verify(1, getRequestedFor(urlEqualTo("/repos/octocat/Hello-World/commits/main")));
		assertThat(GITHUB.getAllServeEvents()).hasSize(2);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private ResultActions submit(String url, boolean force) throws Exception {
		String body = "{\"url\":\"" + url + "\",\"forceRescan\":" + force + "}";
		return mockMvc.perform(post("/api/v1/repositories/analyze").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String submitAndGet(String url, boolean force, String jsonPath) throws Exception {
		String json = submit(url, force).andExpect(status().is2xxSuccessful()).andReturn().getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(json, jsonPath);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private static void stubRepository(String owner, String name, String json) {
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/" + owner + "/" + name)).willReturn(jsonResponse(json)));
	}

	private static void stubCommit(String owner, String name, String ref, String sha) {
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/" + owner + "/" + name + "/commits/" + ref))
			.willReturn(jsonResponse("{\"sha\": \"" + sha + "\"}")));
	}

	private static String repoJson(String owner, String name, String visibility, long sizeKb, boolean archived,
			boolean disabled) {
		return """
				{"id": 1296269, "name": "%s", "owner": {"login": "%s"}, "default_branch": "main",
				 "visibility": "%s", "private": %s, "size": %d, "archived": %s, "disabled": %s}
				""".formatted(name, owner, visibility, !visibility.equals("public"), sizeKb, archived, disabled);
	}

	private static ResponseDefinitionBuilder jsonResponse(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

}
