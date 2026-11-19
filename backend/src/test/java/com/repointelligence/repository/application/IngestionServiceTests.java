package com.repointelligence.repository.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.model.IngestionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.domain.FileRecord;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.FileRecordRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.repository.infrastructure.storage.WorkspaceManager;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.TarGzBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ingestion end to end: WireMock serves the tarball, the real extractor writes a real workspace
 * and the file catalogue lands in PostgreSQL.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class IngestionServiceTests {

	private static final String SHA = "3".repeat(40);

	private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

	private static final Path WORKSPACE_ROOT;

	static {
		GITHUB.start();
		try {
			WORKSPACE_ROOT = Files.createTempDirectory("repointel-ingestion-test");
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("github.api-base-url", () -> "http://localhost:" + GITHUB.port());
		registry.add("github.allowed-hosts", () -> "localhost");
		registry.add("github.require-https", () -> "false");
		registry.add("repository.workspace.root", WORKSPACE_ROOT::toString);
		registry.add("repository.ingestion.max-tarball-size-mb", () -> "1");
		registry.add("repository.ingestion.max-file-size-kb", () -> "4");
	}

	@AfterAll
	static void stop() throws IOException {
		GITHUB.stop();
		deleteTree(WORKSPACE_ROOT);
	}

	@Autowired
	private IngestionService ingestion;

	@Autowired
	private WorkspaceManager workspaces;

	@Autowired
	private TrackedRepositoryRepository repositories;

	@Autowired
	private RepositorySnapshotRepository snapshots;

	@Autowired
	private AnalysisRunRepository runs;

	@Autowired
	private FileRecordRepository files;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID snapshotId;

	private UUID runId;

	@BeforeEach
	void seed() throws IOException {
		GITHUB.resetAll();
		jdbc.update("DELETE FROM repository");
		clearDirectory(WORKSPACE_ROOT);
		TrackedRepository repository = repositories.save(new TrackedRepository("o", "r", "https://github.com/o/r"));
		RepositorySnapshot snapshot = snapshots.save(new RepositorySnapshot(repository, SHA));
		snapshotId = snapshot.getId();
		runId = runs.save(new AnalysisRun(snapshot, RunStatus.QUEUED)).getId();
	}

	@AfterEach
	void cleanUp() throws IOException {
		jdbc.update("DELETE FROM repository");
		clearDirectory(WORKSPACE_ROOT);
	}

	// ---- success -------------------------------------------------------------------------------

	@Test
	void ingestsTheSnapshotIntoAWorkspaceAndCataloguesEveryFile() throws IOException {
		stubTarball(sampleRepository());

		IngestionResult result = ingestion.ingest(runId);

		assertThat(result.succeeded()).isTrue();
		assertThat(result.workspace()).isEqualTo(workspaces.workspaceDir(snapshotId));
		// extracted text files are on disk; binary, oversized, excluded and linked entries are not
		assertThat(Files.readString(result.workspace().resolve("src/main/java/App.java"))).isEqualTo("class App {}");
		assertThat(result.workspace().resolve("pom.xml")).exists();
		assertThat(result.workspace().resolve("logo.png")).doesNotExist();
		assertThat(result.workspace().resolve("big.txt")).doesNotExist();
		assertThat(result.workspace().resolve("node_modules")).doesNotExist();
		assertThat(result.workspace().resolve("link")).doesNotExist();

		RepositorySnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
		assertThat(snapshot.getIngestionStatus()).isEqualTo(IngestionStatus.INGESTED);
		assertThat(snapshot.getFileCount()).isEqualTo(7);
		// pom 10 + App 12 + AppTest 18 + README 5 + logo 8 + big 10000 + link 0
		assertThat(snapshot.getTotalBytes()).isEqualTo(10_053);
		assertThat(snapshot.getIngestedAt()).isNotNull();

		Map<String, FileRecord> records = files.findBySnapshotId(snapshotId)
			.stream()
			.collect(Collectors.toMap(FileRecord::getPath, Function.identity()));
		assertThat(records).containsOnlyKeys("pom.xml", "src/main/java/App.java", "src/test/java/AppTest.java",
				"README.md", "logo.png", "big.txt", "link");
		assertThat(records.get("src/main/java/App.java").getFileType()).isEqualTo(FileType.SOURCE);
		assertThat(records.get("src/main/java/App.java").getLanguage()).isEqualTo("Java");
		assertThat(records.get("src/main/java/App.java").getContentHash()).hasSize(64);
		assertThat(records.get("src/test/java/AppTest.java").getFileType()).isEqualTo(FileType.TEST);
		assertThat(records.get("pom.xml").getFileType()).isEqualTo(FileType.BUILD);
		assertThat(records.get("README.md").getFileType()).isEqualTo(FileType.DOCS);
		assertThat(records.get("logo.png").isBinary()).isTrue();
		assertThat(records.get("big.txt").getSkippedReason()).startsWith("exceeds max-file-size-kb");
		assertThat(records.get("link").getSkippedReason()).isEqualTo("symbolic link");

		AnalysisRun run = runs.findById(runId).orElseThrow();
		assertThat(run.getStatus()).isEqualTo(RunStatus.INGESTING);
		assertThat(run.getStartedAt()).isNotNull();
		assertThat(downloadFiles()).isEmpty();
	}

	@Test
	void anAlreadyIngestedSnapshotWithAWorkspaceIsNotDownloadedAgain() throws IOException {
		stubTarball(sampleRepository());
		ingestion.ingest(runId);
		UUID secondRun = runs.save(new AnalysisRun(snapshots.findById(snapshotId).orElseThrow(), RunStatus.QUEUED))
			.getId();

		IngestionResult second = ingestion.ingest(secondRun);

		assertThat(second.succeeded()).isTrue();
		assertThat(second.workspace()).exists();
		GITHUB.verify(1, getRequestedFor(urlEqualTo("/repos/o/r/tarball/" + SHA)));
		assertThat(runs.findById(secondRun).orElseThrow().getStatus()).isEqualTo(RunStatus.INGESTING);
	}

	@Test
	void aMissingWorkspaceIsRebuiltFromTheTarballWithoutDuplicatingFileRecords() throws IOException {
		stubTarball(sampleRepository());
		ingestion.ingest(runId);
		long before = files.countBySnapshotId(snapshotId);
		workspaces.delete(snapshotId);
		UUID secondRun = runs.save(new AnalysisRun(snapshots.findById(snapshotId).orElseThrow(), RunStatus.QUEUED))
			.getId();

		IngestionResult second = ingestion.ingest(secondRun);

		assertThat(second.succeeded()).isTrue();
		assertThat(second.workspace().resolve("src/main/java/App.java")).exists();
		GITHUB.verify(2, getRequestedFor(urlEqualTo("/repos/o/r/tarball/" + SHA)));
		assertThat(files.countBySnapshotId(snapshotId)).isEqualTo(before);
		assertThat(snapshots.findById(snapshotId).orElseThrow().getIngestionStatus()).isEqualTo(IngestionStatus.INGESTED);
	}

	// ---- rejection and failure -----------------------------------------------------------------

	@Test
	void anOversizedArchiveRejectsTheSnapshotWithAReadableLimitMessage() throws IOException {
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200).withBody(new byte[2 * 1024 * 1024])));

		IngestionResult result = ingestion.ingest(runId);

		assertThat(result.status()).isEqualTo(IngestionResult.Status.REJECTED);
		assertThat(result.failureReason()).contains("exceeds the maximum allowed size of 1 MB");
		RepositorySnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
		assertThat(snapshot.getIngestionStatus()).isEqualTo(IngestionStatus.REJECTED);
		assertThat(snapshot.getIngestionError()).isEqualTo(result.failureReason());
		AnalysisRun run = runs.findById(runId).orElseThrow();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getFailureReason()).isEqualTo(result.failureReason());
		assertThat(run.getCompletedAt()).isNotNull();
		assertThat(files.countBySnapshotId(snapshotId)).isZero();
		assertThat(workspaces.exists(snapshotId)).isFalse();
		assertThat(downloadFiles()).isEmpty();
	}

	@Test
	void aMaliciousArchiveRejectsTheSnapshotAndLeavesNothingOnDisk() throws IOException {
		stubTarball(new TarGzBuilder().file("ok.txt", "fine")
			.rawFile("octocat-hello-0123456/../../evil.txt", "pwned".getBytes())
			.build());

		IngestionResult result = ingestion.ingest(runId);

		assertThat(result.status()).isEqualTo(IngestionResult.Status.REJECTED);
		assertThat(result.failureReason()).contains("path traversal");
		assertThat(snapshots.findById(snapshotId).orElseThrow().getIngestionStatus()).isEqualTo(IngestionStatus.REJECTED);
		assertThat(workspaces.exists(snapshotId)).isFalse();
		assertThat(WORKSPACE_ROOT.getParent().resolve("evil.txt")).doesNotExist();
		assertThat(WORKSPACE_ROOT.resolve("evil.txt")).doesNotExist();
		assertThat(files.countBySnapshotId(snapshotId)).isZero();
	}

	@Test
	void aGitHubRateLimitFailsTheRunAndRecordsWhenToRetry() throws IOException {
		long reset = Instant.now().plusSeconds(1800).getEpochSecond();
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(403)
			.withHeader("X-RateLimit-Remaining", "0")
			.withHeader("X-RateLimit-Reset", String.valueOf(reset))));

		IngestionResult result = ingestion.ingest(runId);

		assertThat(result.status()).isEqualTo(IngestionResult.Status.FAILED);
		AnalysisRun run = runs.findById(runId).orElseThrow();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getRetryAfter()).isEqualTo(Instant.ofEpochSecond(reset));
		assertThat(run.getFailureReason()).contains("rate limit");
		assertThat(snapshots.findById(snapshotId).orElseThrow().getIngestionStatus()).isEqualTo(IngestionStatus.FAILED);
	}

	@Test
	void aCommitThatIsNoLongerAvailableFailsTheRun() throws IOException {
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(404)));

		IngestionResult result = ingestion.ingest(runId);

		assertThat(result.status()).isEqualTo(IngestionResult.Status.FAILED);
		assertThat(result.failureReason()).isEqualTo("The repository or commit is no longer available on GitHub.");
	}

	@Test
	void anUnexpectedFailureIsReportedGenericallyWithoutLeakingInternals() throws IOException {
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200).withBody("this is not a gzip file")));

		IngestionResult result = ingestion.ingest(runId);

		assertThat(result.status()).isEqualTo(IngestionResult.Status.FAILED);
		assertThat(result.failureReason()).startsWith("Ingestion failed unexpectedly (")
			.doesNotContain(WORKSPACE_ROOT.toString());
		RepositorySnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
		assertThat(snapshot.getIngestionStatus()).isEqualTo(IngestionStatus.FAILED);
		assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(workspaces.exists(snapshotId)).isFalse();
		assertThat(downloadFiles()).isEmpty();
	}

	@Test
	void aFailedSnapshotCanBeIngestedAgainByALaterRun() throws IOException {
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(503)));
		assertThat(ingestion.ingest(runId).status()).isEqualTo(IngestionResult.Status.FAILED);
		GITHUB.resetAll();
		stubTarball(sampleRepository());
		UUID retry = runs.save(new AnalysisRun(snapshots.findById(snapshotId).orElseThrow(), RunStatus.QUEUED)).getId();

		IngestionResult second = ingestion.ingest(retry);

		assertThat(second.succeeded()).isTrue();
		assertThat(snapshots.findById(snapshotId).orElseThrow().getIngestionStatus()).isEqualTo(IngestionStatus.INGESTED);
		assertThat(snapshots.findById(snapshotId).orElseThrow().getIngestionError()).isNull();
	}

	// ---- cleanup -------------------------------------------------------------------------------

	@Test
	void theWorkspaceIsDeletedWhenTheRunIsReleased() throws IOException {
		stubTarball(sampleRepository());
		IngestionResult result = ingestion.ingest(runId);
		assertThat(result.workspace()).exists();

		workspaces.releaseAfterRun(snapshotId);

		assertThat(workspaces.exists(snapshotId)).isFalse();
		// the catalogue survives: source is rebuilt on demand from the immutable SHA
		assertThat(files.countBySnapshotId(snapshotId)).isEqualTo(7);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static byte[] sampleRepository() {
		byte[] big = new byte[10_000];
		java.util.Arrays.fill(big, (byte) 'z');
		return new TarGzBuilder().file("pom.xml", "<project/>")
			.file("src/main/java/App.java", "class App {}")
			.file("src/test/java/AppTest.java", "class AppTest {}!!")
			.file("README.md", "# Hi!")
			.file("logo.png", new byte[] { 'P', 'N', 'G', 0, 1, 2, 3, 4 })
			.file("big.txt", big)
			.file("node_modules/dep/index.js", "module.exports = 1")
			.symlink("link", "/etc/passwd")
			.build();
	}

	private void stubTarball(byte[] archive) {
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(200)
			.withHeader("Content-Type", "application/x-gzip")
			.withBody(archive)));
	}

	private List<String> downloadFiles() throws IOException {
		Path downloads = WORKSPACE_ROOT.resolve("_downloads");
		if (!Files.isDirectory(downloads)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(downloads)) {
			return files.map(p -> p.getFileName().toString()).toList();
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
