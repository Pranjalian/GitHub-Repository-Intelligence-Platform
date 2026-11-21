package com.repointelligence.analysis.orchestration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.repointelligence.support.StubAnalyzers.analyzer;
import static com.repointelligence.support.StubAnalyzers.tech;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.repointelligence.analysis.capability.CapabilityMatrixBuilder;
import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerCapability;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerCapabilityRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.ModuleRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.analysis.registry.AnalyzerRegistry;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DependencyResult;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.ModuleResult;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.IngestionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.application.IngestionService;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.FileRecordRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.repository.infrastructure.storage.WorkspaceManager;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.StubAnalyzers.StubAnalyzer;
import com.repointelligence.support.StubAnalyzers.StubDetector;
import com.repointelligence.support.TarGzBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The orchestrator with a real ingestion (WireMock tarball), real PostgreSQL and stub analyzers: fault
 * isolation, time budgets, cancellation, persistence rules, recovery.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class AnalysisOrchestratorTests {

	private static final String SHA = "5".repeat(40);

	private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

	private static final Path WORKSPACE_ROOT;

	static {
		GITHUB.start();
		try {
			WORKSPACE_ROOT = Files.createTempDirectory("repointel-orchestrator-test");
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
		// runs are started explicitly by these tests, never by the event listener
		registry.add("analysis.jobs.enabled", () -> "false");
	}

	@AfterAll
	static void stop() throws IOException {
		GITHUB.stop();
		deleteTree(WORKSPACE_ROOT);
	}

	@Autowired
	private IngestionService ingestion;

	@Autowired
	private AnalysisResultWriter writer;

	@Autowired
	private RunCancellationRegistry cancellations;

	@Autowired
	private IsolatedRunner runner;

	@Autowired
	private PlatformTransactionManager txManager;

	@Autowired
	private Clock clock;

	@Autowired
	private WorkspaceManager workspaces;

	@Autowired
	private TrackedRepositoryRepository repositories;

	@Autowired
	private RepositorySnapshotRepository snapshots;

	@Autowired
	private AnalysisRunRepository runs;

	@Autowired
	private AnalyzerExecutionRepository executions;

	@Autowired
	private AnalysisFindingRepository findings;

	@Autowired
	private DependencyRecordRepository dependencies;

	@Autowired
	private ModuleRecordRepository modules;

	@Autowired
	private TechnologyFindingRepository technologies;

	@Autowired
	private AnalyzerCapabilityRepository capabilities;

	@Autowired
	private FileRecordRepository files;

	@Autowired
	private RunRecoveryService recovery;

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
		snapshot.setBranch("main");
		snapshots.save(snapshot);
		snapshotId = snapshot.getId();
		runId = runs.save(new AnalysisRun(snapshot, RunStatus.QUEUED)).getId();
		stubTarball();
	}

	@AfterEach
	void cleanUp() throws IOException {
		jdbc.update("DELETE FROM repository");
		clearDirectory(WORKSPACE_ROOT);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private AnalysisOrchestrator orchestrator(List<TechnologyDetector> detectors, List<Analyzer> analyzers) {
		return orchestrator(detectors, analyzers, AnalyzerSettings.defaults(), Duration.ofMinutes(5), writer);
	}

	private AnalysisOrchestrator orchestrator(List<TechnologyDetector> detectors, List<Analyzer> analyzers,
			AnalyzerSettings settings, Duration maxDuration, AnalysisResultWriter writer) {
		AnalyzerRegistry registry = new AnalyzerRegistry(detectors, analyzers, settings);
		return new AnalysisOrchestrator(new AnalysisJobProperties(true, 2, 100, maxDuration), clock,
				ingestion, workspaces, registry, new CapabilityMatrixBuilder(registry, settings), settings,
				writer, cancellations, runner, runs, files, txManager);
	}

	private StubDetector javaDetector() {
		return new StubDetector("manifest-detector", "1.0.0").behaviour(ctx -> List.of(
				tech("Java", TechnologyKind.LANGUAGE), tech("Maven", TechnologyKind.BUILD_TOOL)));
	}

	private static FindingResult finding(AnalysisCategory category, String rule, String path, int line) {
		return new FindingResult(category, rule, Severity.MEDIUM, Confidence.LIKELY, "Finding " + rule, "desc " + rule,
				path, null, line, line, Map.of("excerpt", "line " + line), AnalysisMethod.PARSER, null);
	}

	private StubAnalyzer structure() {
		return analyzer("generic-structure", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC).behaviour(ctx -> {
			AnalyzerResult.Builder result = AnalyzerResult.builder()
				.module(new ModuleResult("root", ".", "pom.xml", Ecosystem.MAVEN, null, List.of()))
				.configSummary(Map.of("files", ctx.files().size()));
			return result.build();
		});
	}

	private StubAnalyzer pmd() {
		return analyzer("pmd-java", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC).languages("Java")
			.behaviour(ctx -> AnalyzerResult.builder()
				.finding(finding(AnalysisCategory.CODE_QUALITY, "GodClass", "src/main/java/App.java", 1))
				.finding(finding(AnalysisCategory.CODE_QUALITY, "TooManyMethods", "src/main/java/App.java", 5))
				.limitation("no auxclasspath")
				.build());
	}

	private StubAnalyzer mavenDeps() {
		return analyzer("maven-dependencies", AnalysisCategory.DEPENDENCIES, CapabilityLevel.DEEP).languages("Java")
			.frameworks("Maven")
			.behaviour(ctx -> AnalyzerResult.builder()
				.dependency(new DependencyResult(Ecosystem.MAVEN, "pom.xml", null, "org.springframework.boot",
						"spring-boot-starter-web", null, null, VersionSource.MANAGED_REMOTE_UNRESOLVED, "compile", true,
						false, null, null))
				.build());
	}

	private StubAnalyzer secrets() {
		return analyzer("generic-secrets", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC);
	}

	private AnalysisRun run() {
		return runs.findById(runId).orElseThrow();
	}

	private Map<String, AnalyzerExecution> executionsById() {
		return executions.findByRunId(runId)
			.stream()
			.collect(Collectors.toMap(AnalyzerExecution::getAnalyzerId, e -> e));
	}

	private void stubTarball() {
		byte[] archive = new TarGzBuilder().file("pom.xml", "<project/>")
			.file("src/main/java/App.java", "class App {}")
			.file("README.md", "# Hello")
			.build();
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(archive)));
	}

	// ---- the happy path ------------------------------------------------------------------------

	@Test
	void runsEveryApplicableAnalyzerAndStoresTheirResults() {
		orchestrator(List.of(javaDetector()), List.of(structure(), pmd(), mavenDeps(), secrets())).execute(runId);

		AnalysisRun run = run();
		assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
		assertThat(run.getStartedAt()).isNotNull();
		assertThat(run.getCompletedAt()).isNotNull();
		assertThat(run.getFailureReason()).isNull();

		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId).containsOnlyKeys("manifest-detector", "generic-structure", "pmd-java", "maven-dependencies",
				"generic-secrets");
		assertThat(byId.values()).extracting(AnalyzerExecution::getStatus).containsOnly(ExecutionStatus.COMPLETED);
		AnalyzerExecution pmd = byId.get("pmd-java");
		assertThat(pmd.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
		assertThat(pmd.getStartedAt()).isNotNull();
		assertThat(pmd.getLimitations()).containsExactly("no auxclasspath");
		assertThat(byId.get("generic-structure").getConfigSummary()).containsEntry("files", 3);

		assertThat(findings.findBySnapshotIdAndRunId(snapshotId, runId)).extracting(AnalysisFinding::getRuleId)
			.containsExactlyInAnyOrder("GodClass", "TooManyMethods");
		assertThat(findings.findBySnapshotIdAndRunId(snapshotId, runId)).allSatisfy(f -> {
			assertThat(f.getAnalyzerId()).isEqualTo("pmd-java");
			assertThat(f.getFingerprint()).hasSize(64);
		});
		assertThat(dependencies.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(1);
		assertThat(modules.findBySnapshotIdAndRunId(snapshotId, runId)).extracting(m -> m.getModuleName())
			.containsExactly("root");
		assertThat(technologies.findBySnapshotIdAndRunId(snapshotId, runId)).extracting(TechnologyFinding::getTechnology)
			.containsExactlyInAnyOrder("Java", "Maven");

		List<AnalyzerCapability> matrix = capabilities.findBySnapshotIdAndRunId(snapshotId, runId);
		assertThat(matrix).filteredOn(c -> "pmd-java".equals(c.getAnalyzerId()))
			.singleElement()
			.satisfies(c -> assertThat(c.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED));
		// the stub analyzers have no test or architecture analyzer for Java
		assertThat(matrix).filteredOn(c -> c.getCapabilityLevel() == CapabilityLevel.UNSUPPORTED)
			.extracting(AnalyzerCapability::getCategory)
			.containsExactlyInAnyOrder(AnalysisCategory.TESTS, AnalysisCategory.ARCHITECTURE);
		assertThat(matrix).noneMatch(c -> c.getExecutionStatus() == ExecutionStatus.NOT_ANALYZED);

		assertThat(workspaces.exists(snapshotId)).as("workspace released").isFalse();
		assertThat(snapshots.findById(snapshotId).orElseThrow().getIngestionStatus()).isEqualTo(IngestionStatus.INGESTED);
	}

	@Test
	void aRunWithNoAnalyzersAtAllStillCompletes() {
		orchestrator(List.of(), List.of()).execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED);
		assertThat(executions.findByRunId(runId)).isEmpty();
	}

	@Test
	void technologiesReportedByTwoDetectorsAreMergedIntoOneRow() {
		StubDetector extension = new StubDetector("extension-detector", "1")
			.behaviour(ctx -> List.of(new com.repointelligence.analysis.spi.DetectedTechnology("Java",
					TechnologyKind.LANGUAGE, null, Confidence.POSSIBLE, List.of(), null, null)));
		StubDetector manifest = new StubDetector("manifest-detector", "1")
			.behaviour(ctx -> List.of(new com.repointelligence.analysis.spi.DetectedTechnology("Java",
					TechnologyKind.LANGUAGE, "25", Confidence.DETECTED, List.of(), null, null)));

		orchestrator(List.of(extension, manifest), List.of()).execute(runId);

		assertThat(technologies.findBySnapshotIdAndRunId(snapshotId, runId)).singleElement().satisfies(t -> {
			assertThat(t.getConfidence()).isEqualTo(Confidence.DETECTED);
			assertThat(t.getVersion()).isEqualTo("25");
			assertThat(t.getDetectorId()).isEqualTo("manifest-detector");
		});
	}

	// ---- fault isolation -----------------------------------------------------------------------

	@Test
	void oneThrowingAnalyzerDoesNotAffectTheOthers() {
		StubAnalyzer broken = analyzer("broken", AnalysisCategory.TESTS, CapabilityLevel.BASIC).languages("Java")
			.behaviour(ctx -> {
				throw new IllegalStateException("parse crashed, password=hunter2");
			});

		orchestrator(List.of(javaDetector()), List.of(structure(), pmd(), broken)).execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("broken").getStatus()).isEqualTo(ExecutionStatus.FAILED);
		assertThat(byId.get("broken").getErrorSummary()).startsWith("IllegalStateException: parse crashed")
			.doesNotContain("hunter2")
			.doesNotContain("\tat ");
		assertThat(byId.get("pmd-java").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(byId.get("generic-structure").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(findings.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(2);
		assertThat(modules.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(1);
		assertThat(capabilities.findBySnapshotIdAndRunId(snapshotId, runId)).filteredOn(c -> "broken".equals(c.getAnalyzerId()))
			.singleElement()
			.satisfies(c -> {
				assertThat(c.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
				assertThat(c.getLimitationReason()).startsWith("IllegalStateException");
			});
	}

	@Test
	void aWarningFromAnAnalyzerMakesTheRunCompletedWithWarnings() {
		StubAnalyzer warns = analyzer("warns", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC).languages("Java")
			.behaviour(ctx -> AnalyzerResult.builder().warning("1 file failed to parse").build());

		orchestrator(List.of(javaDetector()), List.of(structure(), warns)).execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
		assertThat(executionsById().get("warns").getStatus()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(executionsById().get("warns").getErrorSummary()).isEqualTo("1 file failed to parse");
	}

	@Test
	void whenEveryAnalyzerFailsTheRunFails() {
		StubAnalyzer a = analyzer("a", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC).behaviour(ctx -> {
			throw new IllegalStateException("nope");
		});
		StubAnalyzer b = analyzer("b", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC).behaviour(ctx -> {
			throw new IllegalStateException("nope");
		});

		orchestrator(List.of(), List.of(a, b)).execute(runId);

		AnalysisRun run = run();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getFailureReason()).isEqualTo("None of the analyzers completed successfully.");
		assertThat(executionsById().values()).extracting(AnalyzerExecution::getStatus)
			.containsOnly(ExecutionStatus.FAILED);
	}

	@Test
	void aFailingDetectorIsRecordedAndTheRunContinuesWithWarnings() {
		StubDetector broken = new StubDetector("broken-detector", "1").behaviour(ctx -> {
			throw new IllegalStateException("cannot read pom");
		});

		orchestrator(List.of(broken, javaDetector()), List.of(structure())).execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("broken-detector").getStatus()).isEqualTo(ExecutionStatus.FAILED);
		assertThat(byId.get("broken-detector").getCategory()).isEqualTo(AnalysisCategory.TECHNOLOGY);
		assertThat(technologies.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(2);
		assertThat(byId.get("generic-structure").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
	}

	@Test
	void unavailableDisabledAndNotApplicableAnalyzersAreReportedHonestly() {
		StubAnalyzer semgrep = analyzer("semgrep", AnalysisCategory.SECURITY, CapabilityLevel.BASIC).languages("Java")
			.applicability(Applicability.unavailable("Semgrep CLI not found"));
		StubAnalyzer disabled = analyzer("disabled-one", AnalysisCategory.TESTS, CapabilityLevel.BASIC)
			.languages("Java");
		StubAnalyzer notApplicable = analyzer("needs-gradle", AnalysisCategory.DEPENDENCIES, CapabilityLevel.BASIC)
			.languages("Java")
			.applicability(Applicability.notApplicable());
		AnalyzerSettings settings = new AnalyzerSettings(
				Map.of("disabled-one", new AnalyzerSettings.AnalyzerConfig(false, Map.of())));

		orchestrator(List.of(javaDetector()), List.of(structure(), semgrep, disabled, notApplicable), settings,
				Duration.ofMinutes(5), writer)
			.execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("semgrep").getStatus()).isEqualTo(ExecutionStatus.UNAVAILABLE);
		assertThat(byId.get("semgrep").getErrorSummary()).isEqualTo("Semgrep CLI not found");
		assertThat(byId.get("disabled-one").getStatus()).isEqualTo(ExecutionStatus.NOT_CONFIGURED);
		assertThat(byId).doesNotContainKey("needs-gradle");
		List<AnalyzerCapability> matrix = capabilities.findBySnapshotIdAndRunId(snapshotId, runId);
		assertThat(matrix).filteredOn(c -> "semgrep".equals(c.getAnalyzerId()))
			.singleElement()
			.satisfies(c -> assertThat(c.getExecutionStatus()).isEqualTo(ExecutionStatus.UNAVAILABLE));
		assertThat(matrix).filteredOn(c -> "disabled-one".equals(c.getAnalyzerId()))
			.singleElement()
			.satisfies(c -> assertThat(c.getExecutionStatus()).isEqualTo(ExecutionStatus.NOT_CONFIGURED));
	}

	// ---- time budget and cancellation ----------------------------------------------------------

	@Test
	void aSlowAnalyzerIsStoppedAtTheDeadlineWhileOthersComplete() {
		AtomicBoolean interrupted = new AtomicBoolean();
		StubAnalyzer slow = analyzer("slow", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC).languages("Java")
			.behaviour(ctx -> {
				try {
					Thread.sleep(60_000);
				}
				catch (InterruptedException ex) {
					interrupted.set(true);
					throw ex;
				}
				return AnalyzerResult.empty();
			});
		long start = System.currentTimeMillis();

		orchestrator(List.of(javaDetector()), List.of(structure(), slow), AnalyzerSettings.defaults(),
				Duration.ofSeconds(3), writer)
			.execute(runId);

		assertThat(System.currentTimeMillis() - start).isLessThan(15_000);
		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("slow").getStatus()).isEqualTo(ExecutionStatus.TIMED_OUT);
		assertThat(byId.get("slow").getErrorSummary()).contains("time budget");
		assertThat(byId.get("generic-structure").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(modules.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(1);
		assertThat(interrupted.get()).as("the slow analyzer thread was interrupted").isTrue();
		assertThat(capabilities.findBySnapshotIdAndRunId(snapshotId, runId)).filteredOn(c -> "slow".equals(c.getAnalyzerId()))
			.singleElement()
			.satisfies(c -> assertThat(c.getExecutionStatus()).isEqualTo(ExecutionStatus.TIMED_OUT));
	}

	@Test
	void cancellingARunningRunEndsItCancelledAndKeepsPartialResults() throws Exception {
		CountDownLatch blockedStarted = new CountDownLatch(1);
		StubAnalyzer blocked = analyzer("blocked", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC)
			.languages("Java")
			.behaviour(ctx -> {
				blockedStarted.countDown();
				while (true) {
					ctx.cancellation().throwIfCancelled();
					Thread.sleep(25);
				}
			});
		AnalysisOrchestrator orchestrator = orchestrator(List.of(javaDetector()), List.of(structure(), blocked));

		CompletableFuture<Void> running = CompletableFuture.runAsync(() -> orchestrator.execute(runId));
		assertThat(blockedStarted.await(15, TimeUnit.SECONDS)).isTrue();
		assertThat(cancellations.cancel(runId)).isTrue();
		running.get(15, TimeUnit.SECONDS);

		AnalysisRun run = run();
		assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
		assertThat(run.getCompletedAt()).isNotNull();
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("blocked").getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		assertThat(byId.get("generic-structure").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(modules.findBySnapshotIdAndRunId(snapshotId, runId)).as("partial results are kept").hasSize(1);
		assertThat(capabilities.findBySnapshotIdAndRunId(snapshotId, runId)).filteredOn(c -> "blocked".equals(c.getAnalyzerId()))
			.singleElement()
			.satisfies(c -> assertThat(c.getExecutionStatus()).isEqualTo(ExecutionStatus.CANCELLED));
		assertThat(workspaces.exists(snapshotId)).isFalse();
		assertThat(cancellations.isRunning(runId)).isFalse();
	}

	@Test
	void aRunCancelledWhileQueuedIsSkippedWithoutDownloading() {
		jdbc.update("UPDATE analysis_run SET status = 'CANCELLED'");
		AtomicInteger calls = new AtomicInteger();
		StubAnalyzer counting = analyzer("counting", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC).behaviour(ctx -> {
			calls.incrementAndGet();
			return AnalyzerResult.empty();
		});

		orchestrator(List.of(), List.of(counting)).execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.CANCELLED);
		assertThat(calls.get()).isZero();
		GITHUB.verify(0, getRequestedFor(urlEqualTo("/repos/o/r/tarball/" + SHA)));
		assertThat(executions.findByRunId(runId)).isEmpty();
	}

	// ---- ingestion failures --------------------------------------------------------------------

	@Test
	void whenIngestionFailsNoAnalyzerRuns() {
		GITHUB.resetAll();
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(404)));
		AtomicInteger calls = new AtomicInteger();
		StubAnalyzer counting = analyzer("counting", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC).behaviour(ctx -> {
			calls.incrementAndGet();
			return AnalyzerResult.empty();
		});

		orchestrator(List.of(), List.of(counting)).execute(runId);

		AnalysisRun run = run();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getFailureReason()).isEqualTo("The repository or commit is no longer available on GitHub.");
		assertThat(calls.get()).isZero();
		assertThat(executions.findByRunId(runId)).isEmpty();
		assertThat(workspaces.exists(snapshotId)).isFalse();
	}

	@Test
	void aRateLimitDuringIngestionFailsTheRunWithARetryTime() {
		long reset = java.time.Instant.now().plusSeconds(1800).getEpochSecond();
		GITHUB.resetAll();
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA)).willReturn(aResponse().withStatus(403)
			.withHeader("X-RateLimit-Remaining", "0")
			.withHeader("X-RateLimit-Reset", String.valueOf(reset))));

		orchestrator(List.of(), List.of(structure())).execute(runId);

		AnalysisRun run = run();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getRetryAfter()).isEqualTo(java.time.Instant.ofEpochSecond(reset));
	}

	// ---- persistence rules ---------------------------------------------------------------------

	@Test
	void secretsAreRedactedAndEvidenceIsBoundedBeforeAnythingIsStored() {
		StubAnalyzer leaky = analyzer("leaky", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC).behaviour(ctx -> {
			Map<String, Object> evidence = new HashMap<>();
			evidence.put("excerpt", "aws.key = AKIAIOSFODNN7EXAMPLE and password=hunter2");
			evidence.put("long", "word ".repeat(1000));
			evidence.put("nested", Map.of("token", "ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
			evidence.put("lines", List.of("secret=topsecret", "fine"));
			evidence.put("count", 3);
			return AnalyzerResult.builder()
				.finding(new FindingResult(AnalysisCategory.SECURITY, "aws-key", Severity.HIGH, Confidence.LIKELY,
						"Key ghp_abcdefghijklmnopqrstuvwxyz0123456789 found", "password=hunter2 in file",
						"application.yml", null, 4, 4, evidence, AnalysisMethod.DETERMINISTIC_SCANNER,
						"Remove token=abcd1234567890"))
				.build();
		});

		orchestrator(List.of(), List.of(leaky)).execute(runId);

		AnalysisFinding stored = findings.findBySnapshotIdAndRunId(snapshotId, runId).get(0);
		String everything = stored.getTitle() + stored.getDescription() + stored.getSuggestedNextStep()
				+ stored.getEvidence();
		assertThat(everything).doesNotContain("AKIAIOSFODNN7EXAMPLE")
			.doesNotContain("hunter2")
			.doesNotContain("ghp_")
			.doesNotContain("topsecret")
			.doesNotContain("abcd1234567890");
		assertThat(everything).contains("***REDACTED***");
		assertThat((String) stored.getEvidence().get("long")).hasSizeLessThanOrEqualTo(500);
		assertThat(stored.getEvidence().get("count")).isEqualTo(3);
	}

	@Test
	void sameLocationFromTwoAnalyzersSharesAFingerprintButBothRowsAreKept() {
		StubAnalyzer one = analyzer("scanner-one", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC)
			.behaviour(ctx -> AnalyzerResult.builder()
				.finding(finding(AnalysisCategory.SECURITY, "rule-one", "application.yml", 4))
				.build());
		StubAnalyzer two = analyzer("scanner-two", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC)
			.behaviour(ctx -> AnalyzerResult.builder()
				.finding(finding(AnalysisCategory.SECURITY, "rule-two", "application.yml", 4))
				.build());

		orchestrator(List.of(), List.of(one, two)).execute(runId);

		List<AnalysisFinding> stored = findings.findBySnapshotIdAndRunId(snapshotId, runId);
		assertThat(stored).hasSize(2);
		assertThat(stored.stream().map(AnalysisFinding::getFingerprint).distinct()).hasSize(1);
		assertThat(stored).extracting(AnalysisFinding::getAnalyzerId).containsExactlyInAnyOrder("scanner-one", "scanner-two");
	}

	@Test
	void aNoisyAnalyzerIsCappedAndSaysSo() {
		StubAnalyzer noisy = analyzer("noisy", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC).languages("Java")
			.behaviour(ctx -> {
				AnalyzerResult.Builder result = AnalyzerResult.builder();
				for (int i = 0; i < 5001; i++) {
					result.finding(finding(AnalysisCategory.CODE_QUALITY, "rule", "src/main/java/App.java", i));
				}
				return result.build();
			});

		orchestrator(List.of(javaDetector()), List.of(noisy)).execute(runId);

		assertThat(findings.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(5000);
		assertThat(executionsById().get("noisy").getLimitations())
			.anyMatch(text -> text.contains("Only the first 5000 of 5001"));
	}

	@Test
	void ifStoringOneAnalyzersResultsFailsOnlyThatAnalyzerFails() {
		StubAnalyzer unstorable = analyzer("unstorable", AnalysisCategory.DEPENDENCIES, CapabilityLevel.DEEP)
			.languages("Java")
			.behaviour(ctx -> AnalyzerResult.builder()
				.finding(finding(AnalysisCategory.DEPENDENCIES, "x", "pom.xml", 1))
				.dependency(new DependencyResult(Ecosystem.MAVEN, "p".repeat(2000), null, null, "x", null, null,
						VersionSource.DECLARED, null, true, false, null, null))
				.build());

		orchestrator(List.of(javaDetector()), List.of(structure(), unstorable)).execute(runId);

		assertThat(run().getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("unstorable").getStatus()).isEqualTo(ExecutionStatus.FAILED);
		assertThat(byId.get("unstorable").getErrorSummary()).startsWith("Results could not be stored");
		assertThat(findings.findBySnapshotIdAndRunId(snapshotId, runId)).as("its transaction rolled back").isEmpty();
		assertThat(byId.get("generic-structure").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(modules.findBySnapshotIdAndRunId(snapshotId, runId)).hasSize(1);
	}

	@Test
	void anUnexpectedOrchestratorFailureStillEndsTheRunAsFailed() {
		AnalysisResultWriter exploding = new AnalysisResultWriter(null, null, null,
				new AnalysisResultProperties(500, 5000)) {
			@Override
			public void saveTechnologies(UUID snapshotId, UUID runId, List<com.repointelligence.analysis.spi.DetectedTechnology> technologies) {
				throw new IllegalStateException("database exploded");
			}

			@Override
			public void failPending(UUID runId, String reason) {
				// nothing pending to update in this test
			}
		};

		orchestrator(List.of(javaDetector()), List.of(structure()), AnalyzerSettings.defaults(), Duration.ofMinutes(5),
				exploding)
			.execute(runId);

		AnalysisRun run = run();
		assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
		assertThat(run.getFailureReason()).isEqualTo("Analysis failed unexpectedly (IllegalStateException).");
		assertThat(run.getCompletedAt()).isNotNull();
		assertThat(workspaces.exists(snapshotId)).isFalse();
	}

	// ---- restart recovery ----------------------------------------------------------------------

	@Test
	void startupRecoveryFailsRunsLeftInFlightAndSweepsTheirWorkspaces() throws IOException {
		RepositorySnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
		jdbc.update("UPDATE analysis_run SET status = 'ANALYZING'");
		AnalysisRun queued = runs.save(new AnalysisRun(snapshot, RunStatus.QUEUED));
		AnalysisRun ingesting = runs.save(new AnalysisRun(snapshot, RunStatus.INGESTING));
		AnalysisRun done = runs.save(new AnalysisRun(snapshot, RunStatus.COMPLETED));
		jdbc.update("UPDATE repository_snapshot SET ingestion_status = 'INGESTING'");
		jdbc.update("""
				INSERT INTO analyzer_execution (run_id, snapshot_id, analyzer_id, analyzer_version, category, status)
				VALUES (?, ?, 'pending-one', '1', 'CODE_QUALITY', 'NOT_ANALYZED'),
				       (?, ?, 'finished-one', '1', 'CODE_QUALITY', 'COMPLETED')
				""", runId, snapshotId, runId, snapshotId);
		Path orphanWorkspace = Files.createDirectories(WORKSPACE_ROOT.resolve(snapshotId.toString()).resolve("src"));
		Files.writeString(orphanWorkspace.resolve("App.java"), "class App {}");

		int recovered = recovery.recover();

		assertThat(recovered).isEqualTo(3);
		for (UUID id : List.of(runId, queued.getId(), ingesting.getId())) {
			AnalysisRun recoveredRun = runs.findById(id).orElseThrow();
			assertThat(recoveredRun.getStatus()).isEqualTo(RunStatus.FAILED);
			assertThat(recoveredRun.getFailureReason()).isEqualTo("Interrupted by restart.");
			assertThat(recoveredRun.getCompletedAt()).isNotNull();
		}
		assertThat(runs.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.COMPLETED);
		RepositorySnapshot recoveredSnapshot = snapshots.findById(snapshotId).orElseThrow();
		assertThat(recoveredSnapshot.getIngestionStatus()).isEqualTo(IngestionStatus.FAILED);
		assertThat(recoveredSnapshot.getIngestionError()).isEqualTo("Interrupted by restart.");
		Map<String, AnalyzerExecution> byId = executionsById();
		assertThat(byId.get("pending-one").getStatus()).isEqualTo(ExecutionStatus.FAILED);
		assertThat(byId.get("pending-one").getErrorSummary()).isEqualTo("Interrupted by restart.");
		assertThat(byId.get("finished-one").getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(WORKSPACE_ROOT.resolve(snapshotId.toString())).as("workspace swept").doesNotExist();
		assertThat(recovery.recover()).as("nothing left to recover").isZero();
	}

	// ---- utilities -----------------------------------------------------------------------------

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
