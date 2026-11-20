package com.repointelligence.analysis.generic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerCapability;
import com.repointelligence.analysis.domain.ModuleRecord;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerCapabilityRepository;
import com.repointelligence.analysis.infrastructure.persistence.ModuleRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.analysis.orchestration.AnalysisOrchestrator;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.TarGzBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
 * The real generic detectors and structure analyzer, run through the real orchestrator against Java
 * and React fixtures: structure results, technologies and capability-matrix rows are all persisted.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class GenericAnalysisIntegrationTests {

	private static final String SHA = "7".repeat(40);

	private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

	private static final Path WORKSPACE_ROOT;

	static {
		GITHUB.start();
		try {
			WORKSPACE_ROOT = Files.createTempDirectory("repointel-generic-it");
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
		registry.add("analysis.jobs.enabled", () -> "false");
	}

	@AfterAll
	static void stop() throws IOException {
		GITHUB.stop();
		deleteTree(WORKSPACE_ROOT);
	}

	@Autowired
	private AnalysisOrchestrator orchestrator;

	@Autowired
	private TrackedRepositoryRepository repositories;

	@Autowired
	private RepositorySnapshotRepository snapshots;

	@Autowired
	private AnalysisRunRepository runs;

	@Autowired
	private AnalysisFindingRepository findings;

	@Autowired
	private ModuleRecordRepository modules;

	@Autowired
	private TechnologyFindingRepository technologies;

	@Autowired
	private AnalyzerCapabilityRepository capabilities;

	@Autowired
	private JdbcTemplate jdbc;

	private UUID runId;

	@BeforeEach
	void seed() {
		GITHUB.resetAll();
		jdbc.update("DELETE FROM repository");
		TrackedRepository repository = repositories.save(new TrackedRepository("o", "r", "https://github.com/o/r"));
		RepositorySnapshot snapshot = snapshots.save(new RepositorySnapshot(repository, SHA));
		snapshot.setBranch("main");
		snapshots.save(snapshot);
		runId = runs.save(new AnalysisRun(snapshot, RunStatus.QUEUED)).getId();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	private void serve(byte[] archive) {
		GITHUB.stubFor(get(urlEqualTo("/repos/o/r/tarball/" + SHA))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(archive)));
	}

	@Test
	void javaFixtureProducesStructureResultsAndMatrixRows() {
		serve(new TarGzBuilder().file("pom.xml", "<project><artifactId>demo</artifactId></project>")
			.file("src/main/java/com/example/DemoApplication.java",
					"package com.example;\npublic class DemoApplication {\n public static void main(String[] a) {}\n}\n")
			.file("src/main/java/com/example/Service.java", "package com.example;\nclass Service {}\n")
			.file("src/test/java/com/example/ServiceTest.java", "package com.example;\nclass ServiceTest {}\n")
			.file("Dockerfile", "FROM eclipse-temurin:21\n")
			.build());

		orchestrator.execute(runId);

		assertThat(runs.findById(runId).orElseThrow().getStatus()).isIn(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_WARNINGS);
		assertThat(technologyNames()).contains("Java", "Maven", "Docker");

		List<ModuleRecord> moduleRecords = modules.findAll();
		assertThat(moduleRecords).hasSize(1);
		assertThat(moduleRecords.get(0).getBuildFilePath()).isEqualTo("pom.xml");

		List<AnalysisFinding> structure = structureFindings();
		assertThat(structure).allSatisfy(f -> assertThat(f.getSeverity()).isEqualTo(Severity.INFO));
		assertThat(structure).extracting(AnalysisFinding::getRuleId).contains("structure-summary", "entry-point");

		assertThat(matrix()).anySatisfy(row -> {
			assertThat(row.getTechnology()).isEqualTo("Java");
			assertThat(row.getCategory()).isEqualTo(AnalysisCategory.STRUCTURE);
			assertThat(row.getAnalyzerId()).isEqualTo(GenericStructureAnalyzer.ID);
		});
	}

	@Test
	void reactFixtureProducesStructureResultsAndMatrixRows() {
		serve(new TarGzBuilder().file("package.json", "{\"name\":\"web\",\"dependencies\":{\"react\":\"18\"}}")
			.file("tsconfig.json", "{}")
			.file("pnpm-lock.yaml", "lockfileVersion: 9\n")
			.file("src/index.tsx", "import React from 'react';\nconsole.log(1);\n")
			.file("src/App.tsx", "export const App = () => null;\n")
			.file("src/App.test.tsx", "test('x', () => {});\n")
			.build());

		orchestrator.execute(runId);

		assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.COMPLETED);
		assertThat(technologyNames()).contains("TypeScript", "npm", "pnpm");

		assertThat(modules.findAll()).extracting(ModuleRecord::getBuildFilePath).containsExactly("package.json");
		assertThat(structureFindings()).extracting(AnalysisFinding::getRuleId)
			.contains("structure-summary", "entry-point");
		assertThat(matrix()).anySatisfy(row -> {
			assertThat(row.getTechnology()).isEqualTo("TypeScript");
			assertThat(row.getCategory()).isEqualTo(AnalysisCategory.STRUCTURE);
			assertThat(row.getAnalyzerId()).isEqualTo(GenericStructureAnalyzer.ID);
		});
	}

	private List<String> technologyNames() {
		return technologies.findAll().stream().map(TechnologyFinding::getTechnology).toList();
	}

	private List<AnalysisFinding> structureFindings() {
		return findings.findAll().stream().filter(f -> f.getCategory() == AnalysisCategory.STRUCTURE).toList();
	}

	private List<AnalyzerCapability> matrix() {
		return capabilities.findAll();
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
