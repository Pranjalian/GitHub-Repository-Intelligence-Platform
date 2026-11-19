package com.repointelligence.analysis.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.analysis.spi.ModuleResult;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.StubAnalyzers;
import com.repointelligence.support.TarGzBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole pipeline over HTTP: submit, let the real after-commit listener start the run on the job
 * executor, and watch it through the status endpoint.
 */
@Import({ EmbeddedPostgresConfig.class, AnalysisRunApiTests.PipelineConfig.class })
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisRunApiTests {

	private static final String SHA = "6".repeat(40);

	private static final String URL = "https://github.com/octocat/Hello-World";

	private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

	private static final Path WORKSPACE_ROOT;

	private static final Gate GATE = new Gate();

	static {
		GITHUB.start();
		try {
			WORKSPACE_ROOT = Files.createTempDirectory("repointel-run-api-test");
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	/** Holds the analyzer inside {@code analyze} so tests can observe and cancel a running analysis. */
	static final class Gate {

		private volatile CountDownLatch entered = new CountDownLatch(1);

		private volatile CountDownLatch release = new CountDownLatch(1);

		private volatile boolean armed;

		void arm() {
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
			armed = true;
		}

		void disarm() {
			armed = false;
			release.countDown();
		}

		boolean awaitEntered() throws InterruptedException {
			return entered.await(20, TimeUnit.SECONDS);
		}

		void pass(CancellationToken token) throws Exception {
			if (!armed) {
				return;
			}
			entered.countDown();
			while (!release.await(25, TimeUnit.MILLISECONDS)) {
				token.throwIfCancelled();
			}
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PipelineConfig {

		@Bean
		TechnologyDetector e2eDetector() {
			return new StubAnalyzers.StubDetector("e2e-detector", "1.0.0")
				.behaviour(ctx -> List.of(StubAnalyzers.tech("Java", TechnologyKind.LANGUAGE)));
		}

		@Bean
		Analyzer e2eStructure() {
			return StubAnalyzers.analyzer("e2e-structure", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC)
				.behaviour(ctx -> {
					GATE.pass(ctx.cancellation());
					return AnalyzerResult.builder()
						.module(new ModuleResult("root", ".", "pom.xml", Ecosystem.MAVEN, null, List.of()))
						.build();
				});
		}

	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("github.api-base-url", () -> "http://localhost:" + GITHUB.port());
		registry.add("github.allowed-hosts", () -> "localhost");
		registry.add("github.require-https", () -> "false");
		registry.add("repository.workspace.root", WORKSPACE_ROOT::toString);
	}

	@AfterAll
	static void stop() throws IOException {
		GITHUB.stop();
		deleteTree(WORKSPACE_ROOT);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() throws IOException {
		GATE.disarm();
		jdbc.update("DELETE FROM repository");
		clearDirectory(WORKSPACE_ROOT);
		GITHUB.resetAll();
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/Hello-World")).willReturn(json("""
				{"id": 1296269, "name": "Hello-World", "owner": {"login": "octocat"}, "default_branch": "main",
				 "visibility": "public", "private": false, "size": 10, "archived": false, "disabled": false}
				""")));
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/Hello-World/commits/main"))
			.willReturn(json("{\"sha\": \"" + SHA + "\"}")));
		stubTarball(400);
	}

	@AfterEach
	void tearDown() throws Exception {
		GATE.disarm();
		// let any in-flight run finish before the next test wipes the database
		waitUntilNothingIsRunning();
		jdbc.update("DELETE FROM repository");
		clearDirectory(WORKSPACE_ROOT);
	}

	// ---- the pipeline end to end ---------------------------------------------------------------

	@Test
	void aSubmittedRunProgressesThroughIngestingAndAnalyzingToCompleted() throws Exception {
		GATE.arm();
		String body = submit().andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String runId = JsonPath.read(body, "$.analysisRunId");
		String repositoryId = JsonPath.read(body, "$.repositoryId");

		List<String> seen = new ArrayList<>();
		boolean released = false;
		String last = null;
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			String json = mockMvc.perform(get("/api/v1/analysis-runs/" + runId)).andExpect(status().isOk()).andReturn()
				.getResponse()
				.getContentAsString();
			String status = JsonPath.read(json, "$.status");
			if (!status.equals(last)) {
				seen.add(status);
				last = status;
			}
			if (status.equals("ANALYZING") && !released && GATE.awaitEntered()) {
				released = true;
				GATE.disarm();
			}
			if (List.of("COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED", "CANCELLED").contains(status)) {
				break;
			}
			Thread.sleep(40);
		}

		assertThat(seen).as("observed statuses").contains("INGESTING", "ANALYZING", "COMPLETED");
		assertThat(seen.indexOf("INGESTING")).isLessThan(seen.indexOf("ANALYZING"));
		assertThat(seen.indexOf("ANALYZING")).isLessThan(seen.indexOf("COMPLETED"));
		assertThat(seen).doesNotContain("FAILED");

		mockMvc.perform(get("/api/v1/analysis-runs/" + runId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId))
			.andExpect(jsonPath("$.repositoryId").value(repositoryId))
			.andExpect(jsonPath("$.snapshotId").isNotEmpty())
			.andExpect(jsonPath("$.commitSha").value(SHA))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.phase").value("FINISHED"))
			.andExpect(jsonPath("$.failureReason").doesNotExist())
			.andExpect(jsonPath("$.requestedAt").isNotEmpty())
			.andExpect(jsonPath("$.startedAt").isNotEmpty())
			.andExpect(jsonPath("$.completedAt").isNotEmpty())
			.andExpect(jsonPath("$.executions.length()").value(2))
			.andExpect(jsonPath("$.executions[0].analyzerId").value("e2e-detector"))
			.andExpect(jsonPath("$.executions[0].category").value("TECHNOLOGY"))
			.andExpect(jsonPath("$.executions[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.executions[1].analyzerId").value("e2e-structure"))
			.andExpect(jsonPath("$.executions[1].category").value("STRUCTURE"))
			.andExpect(jsonPath("$.executions[1].status").value("COMPLETED"))
			.andExpect(jsonPath("$.executions[1].durationMs").isNumber())
			.andExpect(jsonPath("$.executions[1].startedAt").isNotEmpty());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM module_record", Integer.class)).isEqualTo(1);
		assertThat(workspaceDirectories()).as("workspace released after the run").isEmpty();
	}

	@Test
	void theJobAlwaysSeesTheCommittedRunSoSeveralQuickSubmissionsNeverRace() throws Exception {
		for (int i = 0; i < 4; i++) {
			String name = "repo" + i;
			GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/" + name)).willReturn(json("""
					{"id": %d, "name": "%s", "owner": {"login": "octocat"}, "default_branch": "main",
					 "visibility": "public", "private": false, "size": 10, "archived": false, "disabled": false}
					""".formatted(100 + i, name))));
			String sha = String.valueOf(i + 1).repeat(40);
			GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/" + name + "/commits/main"))
				.willReturn(json("{\"sha\": \"" + sha + "\"}")));
			byte[] archive = new TarGzBuilder().file("pom.xml", "<project/>").build();
			GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/" + name + "/tarball/" + sha))
				.willReturn(aResponse().withStatus(200).withBody(archive)));
			mockMvc.perform(post("/api/v1/repositories/analyze").contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://github.com/octocat/" + name + "\"}")).andExpect(status().isAccepted());
		}

		waitUntilNothingIsRunning();

		assertThat(jdbc.queryForList("SELECT status FROM analysis_run", String.class)).hasSize(4)
			.containsOnly("COMPLETED");
	}

	@Test
	void anIngestionFailureShowsUpInTheStatusEndpoint() throws Exception {
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/Hello-World/tarball/" + SHA))
			.willReturn(aResponse().withStatus(404)));
		String runId = JsonPath.read(submit().andReturn().getResponse().getContentAsString(), "$.analysisRunId");

		String json = awaitTerminal(runId);

		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("FAILED");
		assertThat((String) JsonPath.read(json, "$.failureReason"))
			.isEqualTo("The repository or commit is no longer available on GitHub.");
		assertThat((String) JsonPath.read(json, "$.phase")).isEqualTo("FINISHED");
	}

	@Test
	void aRateLimitedIngestionReportsWhenToRetry() throws Exception {
		long reset = Instant.now().plusSeconds(1800).getEpochSecond();
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/Hello-World/tarball/" + SHA)).willReturn(aResponse()
			.withStatus(403)
			.withHeader("X-RateLimit-Remaining", "0")
			.withHeader("X-RateLimit-Reset", String.valueOf(reset))));
		String runId = JsonPath.read(submit().andReturn().getResponse().getContentAsString(), "$.analysisRunId");

		String json = awaitTerminal(runId);

		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("FAILED");
		assertThat(Instant.parse(JsonPath.read(json, "$.retryAfter"))).isEqualTo(Instant.ofEpochSecond(reset));
		assertThat((String) JsonPath.read(json, "$.failureReason")).contains("rate limit");
	}

	// ---- reading and cancelling ----------------------------------------------------------------

	@Test
	void unknownAndMalformedRunIdsAreHandled() throws Exception {
		mockMvc.perform(get("/api/v1/analysis-runs/" + UUID.randomUUID())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Analysis run not found."));
		mockMvc.perform(get("/api/v1/analysis-runs/not-a-uuid")).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/analysis-runs/" + UUID.randomUUID() + "/cancel")).andExpect(status().isNotFound());
	}

	@Test
	void cancellingARunningRunEndsItCancelled() throws Exception {
		GATE.arm();
		String runId = JsonPath.read(submit().andReturn().getResponse().getContentAsString(), "$.analysisRunId");
		assertThat(GATE.awaitEntered()).as("the analyzer started").isTrue();

		mockMvc.perform(post("/api/v1/analysis-runs/" + runId + "/cancel")).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.runId").value(runId));
		String json = awaitTerminal(runId);

		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("CANCELLED");
		assertThat((String) JsonPath.read(json, "$.completedAt")).isNotBlank();
		assertThat(JsonPath.<List<String>>read(json, "$.executions[?(@.analyzerId=='e2e-structure')].status"))
			.containsExactly("CANCELLED");
		assertThat(workspaceDirectories()).isEmpty();
	}

	@Test
	void cancellingAQueuedRunCancelsItAtOnceAndASecondCancelConflicts() throws Exception {
		UUID snapshotId = seedSnapshot();
		UUID queued = jdbc.queryForObject(
				"INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'QUEUED') RETURNING id", UUID.class, snapshotId);

		mockMvc.perform(post("/api/v1/analysis-runs/" + queued + "/cancel")).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.phase").value("FINISHED"))
			.andExpect(jsonPath("$.completedAt").isNotEmpty());
		mockMvc.perform(post("/api/v1/analysis-runs/" + queued + "/cancel")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The run has already ended (CANCELLED)."))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void aFinishedRunCannotBeCancelled() throws Exception {
		UUID snapshotId = seedSnapshot();
		UUID done = jdbc.queryForObject(
				"INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'COMPLETED') RETURNING id", UUID.class, snapshotId);

		mockMvc.perform(post("/api/v1/analysis-runs/" + done + "/cancel")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The run has already ended (COMPLETED)."));
	}

	@Test
	void anInFlightRunWithoutAnExecutingJobIsCancelledDirectly() throws Exception {
		UUID snapshotId = seedSnapshot();
		UUID orphan = jdbc.queryForObject(
				"INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'ANALYZING') RETURNING id", UUID.class,
				snapshotId);

		mockMvc.perform(post("/api/v1/analysis-runs/" + orphan + "/cancel")).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void phaseReflectsWhereAnInFlightRunIs() throws Exception {
		UUID snapshotId = seedSnapshot();
		UUID run = jdbc.queryForObject(
				"INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'QUEUED') RETURNING id", UUID.class, snapshotId);

		mockMvc.perform(get("/api/v1/analysis-runs/" + run)).andExpect(jsonPath("$.phase").value("QUEUED"));
		jdbc.update("UPDATE analysis_run SET status = 'INGESTING' WHERE id = ?", run);
		mockMvc.perform(get("/api/v1/analysis-runs/" + run)).andExpect(jsonPath("$.phase").value("INGESTING"));
		jdbc.update("UPDATE analysis_run SET status = 'ANALYZING' WHERE id = ?", run);
		mockMvc.perform(get("/api/v1/analysis-runs/" + run)).andExpect(jsonPath("$.phase").value("DETECTING"));
		jdbc.update("""
				INSERT INTO analyzer_execution (run_id, snapshot_id, analyzer_id, analyzer_version, category, status)
				VALUES (?, ?, 'x', '1', 'STRUCTURE', 'NOT_ANALYZED')
				""", run, snapshotId);
		mockMvc.perform(get("/api/v1/analysis-runs/" + run)).andExpect(jsonPath("$.phase").value("ANALYZING"));
		// nothing executes this seeded run; finish it so teardown does not wait for it
		jdbc.update("UPDATE analysis_run SET status = 'COMPLETED' WHERE id = ?", run);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private org.springframework.test.web.servlet.ResultActions submit() throws Exception {
		return mockMvc.perform(post("/api/v1/repositories/analyze").contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + URL + "\"}"));
	}

	/** A repository and snapshot row for tests that insert runs directly (no event, nothing starts). */
	private UUID seedSnapshot() {
		UUID repo = jdbc.queryForObject(
				"INSERT INTO repository (owner, name, canonical_url) VALUES ('o', 'r', 'https://github.com/o/r') RETURNING id",
				UUID.class);
		return jdbc.queryForObject("INSERT INTO repository_snapshot (repository_id, commit_sha) VALUES (?, ?) RETURNING id",
				UUID.class, repo, "7".repeat(40));
	}

	private String awaitTerminal(String runId) throws Exception {
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			String json = mockMvc.perform(get("/api/v1/analysis-runs/" + runId)).andReturn().getResponse()
				.getContentAsString();
			String status = JsonPath.read(json, "$.status");
			if (List.of("COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED", "CANCELLED").contains(status)) {
				return json;
			}
			Thread.sleep(40);
		}
		throw new AssertionError("run " + runId + " did not finish in time");
	}

	private void waitUntilNothingIsRunning() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			Integer inFlight = jdbc.queryForObject(
					"SELECT count(*) FROM analysis_run WHERE status IN ('QUEUED','INGESTING','ANALYZING')", Integer.class);
			if (inFlight != null && inFlight == 0) {
				return;
			}
			Thread.sleep(50);
		}
	}

	private void stubTarball(int delayMillis) {
		byte[] archive = new TarGzBuilder().file("pom.xml", "<project/>")
			.file("src/main/java/App.java", "class App {}")
			.build();
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/octocat/Hello-World/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/x-gzip")
				.withBody(archive)
				.withFixedDelay(delayMillis)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static List<String> workspaceDirectories() throws IOException {
		try (Stream<Path> children = Files.list(WORKSPACE_ROOT)) {
			return children.filter(Files::isDirectory)
				.map(p -> p.getFileName().toString())
				.filter(name -> !name.equals("_downloads"))
				.toList();
		}
	}

	private static void clearDirectory(Path directory) throws IOException {
		try (Stream<Path> children = Files.list(directory)) {
			for (Path child : children.toList()) {
				deleteTree(child);
			}
		}
	}

	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

}
