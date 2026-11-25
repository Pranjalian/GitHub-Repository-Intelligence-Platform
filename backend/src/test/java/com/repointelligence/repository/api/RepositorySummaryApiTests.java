package com.repointelligence.repository.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.rag.indexing.IndexStatus;
import com.repointelligence.rag.indexing.SnapshotIndex;
import com.repointelligence.rag.indexing.SnapshotIndexRepository;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.support.EmbeddedPostgresConfig;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The dashboard additions of Step 7.3: the repository list summary, the KPI stats and the report extras. */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class RepositorySummaryApiTests {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("analysis.jobs.enabled", () -> "false");
		registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

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
	private TechnologyFindingRepository technologies;

	@Autowired
	private SnapshotIndexRepository indexes;

	private int fingerprint;

	private long shaCounter = 0xabc000L;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM snapshot_index");
		jdbc.update("DELETE FROM repository");
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM snapshot_index");
		jdbc.update("DELETE FROM repository");
	}

	private RepositorySnapshot snapshot(TrackedRepository repository, String sha) {
		RepositorySnapshot snapshot = snapshots.save(new RepositorySnapshot(repository, sha));
		snapshot.setBranch("main");
		return snapshots.save(snapshot);
	}

	private AnalysisRun run(RepositorySnapshot snapshot, RunStatus status, Instant started, Instant completed) {
		AnalysisRun run = new AnalysisRun(snapshot, status);
		run.setStartedAt(started);
		run.setCompletedAt(completed);
		return runs.save(run);
	}

	private void execution(AnalysisRun run, String id, AnalysisCategory category, ExecutionStatus status) {
		executions.save(new AnalyzerExecution(run, run.getSnapshot(), id, "1.0.0", category, status));
	}

	private void finding(AnalysisRun run, AnalysisCategory category, Severity severity) {
		findings.save(new AnalysisFinding(run.getSnapshot(), run, "analyzer-" + category, category, "rule", severity,
				Confidence.LIKELY, "Title", AnalysisMethod.HEURISTIC, "fp-" + fingerprint++));
	}

	private void technology(AnalysisRun run, String name, TechnologyKind kind, Confidence confidence, String share) {
		TechnologyFinding finding = new TechnologyFinding(run.getSnapshot(), run, name, kind, confidence, "detector");
		if (share != null) {
			finding.setSharePercent(new BigDecimal(share));
		}
		technologies.save(finding);
	}

	private String nextSha() {
		return String.format("%040x", ++shaCounter);
	}

	/** A Java repository: every analyzed category, one dependency, an indexed snapshot. */
	private TrackedRepository javaRepository(String name) {
		TrackedRepository repository = repositories.save(new TrackedRepository("octo", name, "https://github.com/octo/" + name));
		RepositorySnapshot snapshot = snapshot(repository, nextSha());
		AnalysisRun run = run(snapshot, RunStatus.COMPLETED, Instant.parse("2026-11-01T10:00:00Z"),
				Instant.parse("2026-11-01T10:00:18Z"));
		execution(run, "pmd", AnalysisCategory.CODE_QUALITY, ExecutionStatus.COMPLETED);
		execution(run, "semgrep", AnalysisCategory.SECURITY, ExecutionStatus.COMPLETED);
		execution(run, "maven", AnalysisCategory.DEPENDENCIES, ExecutionStatus.COMPLETED);
		finding(run, AnalysisCategory.CODE_QUALITY, Severity.HIGH);
		finding(run, AnalysisCategory.CODE_QUALITY, Severity.LOW);
		finding(run, AnalysisCategory.SECURITY, Severity.CRITICAL);
		technology(run, "Java", TechnologyKind.LANGUAGE, Confidence.DETECTED, "84.50");
		technology(run, "HTML", TechnologyKind.LANGUAGE, Confidence.DETECTED, "15.50");
		technology(run, "Spring Boot", TechnologyKind.FRAMEWORK, Confidence.DETECTED, null);
		technology(run, "Thymeleaf", TechnologyKind.FRAMEWORK, Confidence.LIKELY, null);
		technology(run, "Zeta", TechnologyKind.FRAMEWORK, Confidence.POSSIBLE, null);
		dependencies.save(new DependencyRecord(snapshot, run, "maven", Ecosystem.MAVEN, "pom.xml", "spring-core",
				VersionSource.DECLARED));
		return repository;
	}

	@Test
	void aJavaRepositoryHasAFullSummary() throws Exception {
		TrackedRepository repository = javaRepository("petclinic");
		indexes.save(completedIndex(repository));

		mockMvc.perform(get("/api/v1/repositories/" + repository.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.primaryLanguage").value("Java"))
			.andExpect(jsonPath("$.summary.primaryLanguageShare").value(84.5))
			.andExpect(jsonPath("$.summary.frameworks", contains("Spring Boot", "Thymeleaf")))
			.andExpect(jsonPath("$.summary.dependencyCount").value(1))
			.andExpect(jsonPath("$.summary.findingsBySeverity.CODE_QUALITY.HIGH").value(1))
			.andExpect(jsonPath("$.summary.findingsBySeverity.CODE_QUALITY.MEDIUM").value(0))
			.andExpect(jsonPath("$.summary.findingsBySeverity.SECURITY.CRITICAL").value(1))
			.andExpect(jsonPath("$.summary.openFindings").value(3))
			.andExpect(jsonPath("$.summary.indexStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.summary.latestRunStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.summary.analysisDurationMs").value(18000));
	}

	private SnapshotIndex completedIndex(TrackedRepository repository) {
		RepositorySnapshot snapshot = snapshots.findFirstByRepositoryIdOrderByCreatedAtDesc(repository.getId()).orElseThrow();
		SnapshotIndex index = new SnapshotIndex(snapshot.getId(), repository.getId(), Instant.now());
		index.complete(IndexStatus.COMPLETED, null, List.of(), Instant.now());
		return index;
	}

	@Test
	void aGenericRepositoryReportsNullsNotZerosForWhatWasNotAnalyzed() throws Exception {
		TrackedRepository repository = repositories.save(new TrackedRepository("octo", "flask", "https://github.com/octo/flask"));
		RepositorySnapshot snapshot = snapshot(repository, "b".repeat(40));
		AnalysisRun run = run(snapshot, RunStatus.COMPLETED_WITH_WARNINGS, Instant.parse("2026-11-01T10:00:00Z"),
				Instant.parse("2026-11-01T10:00:05Z"));
		execution(run, "generic-secrets", AnalysisCategory.SECURITY, ExecutionStatus.COMPLETED);
		execution(run, "pmd", AnalysisCategory.CODE_QUALITY, ExecutionStatus.NOT_SUPPORTED);
		technology(run, "Python", TechnologyKind.LANGUAGE, Confidence.DETECTED, "100.00");

		mockMvc.perform(get("/api/v1/repositories/" + repository.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.primaryLanguage").value("Python"))
			.andExpect(jsonPath("$.summary.dependencyCount").value(nullValue()))
			.andExpect(jsonPath("$.summary.findingsBySeverity.CODE_QUALITY").value(nullValue()))
			.andExpect(jsonPath("$.summary.findingsBySeverity.SECURITY.HIGH").value(0))
			.andExpect(jsonPath("$.summary.openFindings").value(0))
			.andExpect(jsonPath("$.summary.indexStatus").value("NOT_INDEXED"));
	}

	@Test
	void aRepositoryWithoutACompletedRunHasANullSummary() throws Exception {
		TrackedRepository failed = repositories.save(new TrackedRepository("octo", "broken", "https://github.com/octo/broken"));
		AnalysisRun failedRun = run(snapshot(failed, "c".repeat(40)), RunStatus.FAILED, Instant.now(), Instant.now());
		failedRun.setFailureReason("boom");
		TrackedRepository fresh = repositories.save(new TrackedRepository("octo", "fresh", "https://github.com/octo/fresh"));

		mockMvc.perform(get("/api/v1/repositories/" + failed.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary").value(nullValue()))
			.andExpect(jsonPath("$.latestSnapshot.latestRun.status").value("FAILED"));
		mockMvc.perform(get("/api/v1/repositories/" + fresh.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary").value(nullValue()))
			.andExpect(jsonPath("$.latestSnapshot").value(nullValue()));
	}

	@Test
	void theSummaryCountsFromTheLastCompletedRunEvenWhenANewerRunIsActive() throws Exception {
		TrackedRepository repository = javaRepository("rescan");
		RepositorySnapshot newer = snapshot(repository, "e".repeat(40));
		run(newer, RunStatus.ANALYZING, Instant.now(), null);

		mockMvc.perform(get("/api/v1/repositories/" + repository.getId()))
			.andExpect(jsonPath("$.summary.latestRunStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.latestSnapshot.latestRun.status").value("ANALYZING"));
	}

	@Test
	void queryCountDoesNotGrowWithPageSize() throws Exception {
		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		for (int i = 0; i < 10; i++) {
			javaRepository("many" + i);
		}
		// both pages are full, so both run the page and the count query; only a per-row query could differ
		long two = statementsFor("/api/v1/repositories?page=0&size=2", sessionFactory);
		long ten = statementsFor("/api/v1/repositories?page=0&size=10", sessionFactory);

		assertThat(ten).as("statements for a page of 10 rows vs a page of 2").isEqualTo(two);
	}

	private long statementsFor(String url, SessionFactory sessionFactory) throws Exception {
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get(url)).andExpect(status().isOk());
		return sessionFactory.getStatistics().getPrepareStatementCount();
	}

	@Test
	void statsAreHonestWhenNothingIsAnalyzed() throws Exception {
		mockMvc.perform(get("/api/v1/repositories/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoriesTotal").value(0))
			.andExpect(jsonPath("$.repositoriesAnalyzed").value(0))
			.andExpect(jsonPath("$.openFindings").value(nullValue()))
			.andExpect(jsonPath("$.activeRuns").value(0));
	}

	@Test
	void statsCountRepositoriesIndexesFindingsAndActiveRuns() throws Exception {
		TrackedRepository indexed = javaRepository("indexed");
		indexes.save(completedIndex(indexed));
		javaRepository("plain");
		TrackedRepository running = repositories.save(new TrackedRepository("octo", "running", "https://github.com/octo/running"));
		run(snapshot(running, "f".repeat(40)), RunStatus.INGESTING, Instant.now(), null);

		mockMvc.perform(get("/api/v1/repositories/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repositoriesTotal").value(3))
			.andExpect(jsonPath("$.repositoriesAnalyzed").value(2))
			.andExpect(jsonPath("$.indexedForSearch").value(1))
			.andExpect(jsonPath("$.openFindings").value(6))
			.andExpect(jsonPath("$.activeRuns").value(1));
	}

	@Test
	void theReportCarriesDurationAndTheDependencyCountOrNull() throws Exception {
		TrackedRepository repository = javaRepository("report");
		RepositorySnapshot snapshot = snapshots.findFirstByRepositoryIdOrderByCreatedAtDesc(repository.getId()).orElseThrow();
		mockMvc.perform(get("/api/v1/repositories/" + repository.getId() + "/reports/" + snapshot.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.run.durationMs").value(18000))
			.andExpect(jsonPath("$.dependencyCount").value(1));
		mockMvc.perform(get("/api/v1/repositories/" + repository.getId() + "/snapshots/" + snapshot.getId() + "/dependencies"))
			.andExpect(jsonPath("$.totalElements").value(1));

		TrackedRepository generic = repositories.save(new TrackedRepository("octo", "generic", "https://github.com/octo/generic"));
		RepositorySnapshot genericSnapshot = snapshot(generic, "9".repeat(40));
		AnalysisRun genericRun = run(genericSnapshot, RunStatus.COMPLETED, null, Instant.now());
		execution(genericRun, "maven", AnalysisCategory.DEPENDENCIES, ExecutionStatus.NOT_SUPPORTED);
		mockMvc.perform(get("/api/v1/repositories/" + generic.getId() + "/reports/" + genericSnapshot.getId()))
			.andExpect(jsonPath("$.dependencyCount").value(nullValue()))
			.andExpect(jsonPath("$.run.durationMs").value(nullValue()));
	}

	@Test
	void anExecutionExplainsWhyItDidNotRun() throws Exception {
		TrackedRepository repository = repositories.save(new TrackedRepository("octo", "reasons", "https://github.com/octo/reasons"));
		AnalysisRun run = run(snapshot(repository, "8".repeat(40)), RunStatus.COMPLETED_WITH_WARNINGS, Instant.now(), Instant.now());
		AnalyzerExecution skipped = new AnalyzerExecution(run, run.getSnapshot(), "jacoco", "1.0.0", AnalysisCategory.TESTS,
				ExecutionStatus.NOT_CONFIGURED);
		skipped.setLimitations(List.of("No committed JaCoCo report"));
		executions.save(skipped);
		execution(run, "plain", AnalysisCategory.STRUCTURE, ExecutionStatus.COMPLETED);

		mockMvc.perform(get("/api/v1/analysis-runs/" + run.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.executions[?(@.analyzerId=='jacoco')].reason").value("No committed JaCoCo report"))
			.andExpect(jsonPath("$.executions[?(@.analyzerId=='plain')].reason").value(contains(nullValue())));
	}

}
