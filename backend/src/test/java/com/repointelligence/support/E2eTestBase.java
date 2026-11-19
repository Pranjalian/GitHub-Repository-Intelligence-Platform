package com.repointelligence.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full HTTP flow for end-to-end tests: submit a URL, poll the run, read the report. GitHub is a WireMock
 * stand-in (repository metadata, commit and tarball endpoints), the database is embedded PostgreSQL, and
 * the real orchestrator and analyzers run. Subclasses share one Spring context.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
public abstract class E2eTestBase {

	protected static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

	protected static final Path WORKSPACE_ROOT;

	static {
		GITHUB.start();
		try {
			WORKSPACE_ROOT = Files.createTempDirectory("repointel-e2e");
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			GITHUB.stop();
			try {
				deleteTree(WORKSPACE_ROOT);
			}
			catch (IOException ignored) {
				// best effort
			}
		}));
	}

	@DynamicPropertySource
	static void e2eProperties(DynamicPropertyRegistry registry) {
		registry.add("github.api-base-url", () -> "http://localhost:" + GITHUB.port());
		registry.add("github.allowed-hosts", () -> "localhost");
		registry.add("github.require-https", () -> "false");
		registry.add("repository.workspace.root", WORKSPACE_ROOT::toString);
		// the submission rate limiter would throttle a busy test class
		registry.add("repository.submission.rate-limit.capacity", () -> "1000");
		registry.add("repository.submission.max-pending-jobs", () -> "1000");
	}

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@BeforeEach
	void resetE2eState() {
		GITHUB.resetAll();
		jdbc.update("DELETE FROM repository");
	}

	@AfterEach
	void cleanE2eState() {
		jdbc.update("DELETE FROM repository");
	}

	/** Result of {@link #analyze}: ids and the final run JSON. */
	public record Analysis(String repositoryId, String snapshotId, String runId, String status, String runJson) {
	}

	protected static void stubGithub(String owner, String name, String sha, byte[] tarball) {
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/" + owner + "/" + name))
			.willReturn(json("{\"id\": 4242, \"name\": \"" + name + "\", \"owner\": {\"login\": \"" + owner
					+ "\"}, \"default_branch\": \"main\", \"visibility\": \"public\", \"private\": false, "
					+ "\"size\": 100, \"archived\": false, \"disabled\": false}")));
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/" + owner + "/" + name + "/commits/main"))
			.willReturn(json("{\"sha\": \"" + sha + "\"}")));
		GITHUB.stubFor(WireMock.get(urlEqualTo("/repos/" + owner + "/" + name + "/tarball/" + sha))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(tarball)));
	}

	/** Stubs GitHub for the repository, submits its URL, and waits for the run to finish. */
	protected Analysis analyze(String owner, String name, String sha, byte[] tarball) throws Exception {
		stubGithub(owner, name, sha, tarball);
		return submitAndWait("https://github.com/" + owner + "/" + name, false);
	}

	protected Analysis submitAndWait(String url, boolean forceRescan) throws Exception {
		String body = mockMvc
			.perform(post("/api/v1/repositories/analyze").contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"" + url + "\",\"forceRescan\":" + forceRescan + "}"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String runId = JsonPath.read(body, "$.analysisRunId");
		String repositoryId = JsonPath.read(body, "$.repositoryId");
		String snapshotId = JsonPath.read(body, "$.snapshotId");
		long deadline = System.currentTimeMillis() + 90_000;
		while (System.currentTimeMillis() < deadline) {
			String json = mockMvc.perform(get("/api/v1/analysis-runs/" + runId)).andReturn().getResponse()
				.getContentAsString();
			String status = JsonPath.read(json, "$.status");
			if (List.of("COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED", "CANCELLED").contains(status)) {
				return new Analysis(repositoryId, snapshotId, runId, status, json);
			}
			Thread.sleep(50);
		}
		throw new AssertionError("run " + runId + " did not finish in time");
	}

	protected String report(Analysis analysis) throws Exception {
		return mockMvc.perform(get("/api/v1/repositories/" + analysis.repositoryId() + "/reports/" + analysis.snapshotId()))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	protected String getJson(String path) throws Exception {
		return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
	}

	protected String dependencies(Analysis analysis, String query) throws Exception {
		return getJson("/api/v1/repositories/" + analysis.repositoryId() + "/snapshots/" + analysis.snapshotId()
				+ "/dependencies?size=500" + query);
	}

	protected String findings(Analysis analysis, String query) throws Exception {
		return getJson("/api/v1/repositories/" + analysis.repositoryId() + "/snapshots/" + analysis.snapshotId()
				+ "/findings?size=100" + query);
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	/** Packs a fixture directory under {@code src/test/resources/fixtures/<name>} into a GitHub-style tarball. */
	public static TarGzBuilder fixture(String name) {
		try {
			Path root = Path.of(E2eTestBase.class.getResource("/fixtures/" + name).toURI());
			TarGzBuilder tar = new TarGzBuilder();
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
					tar.file(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
				}
			}
			return tar;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot load fixture " + name, ex);
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
