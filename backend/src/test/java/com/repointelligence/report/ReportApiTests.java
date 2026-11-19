package com.repointelligence.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.support.EmbeddedPostgresConfig;
import java.util.UUID;
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

/** The report and findings APIs over rows written the way the orchestrator writes them. */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReportApiTests {

	private static final String SHA = "c".repeat(40);

	private static final String OTHER_SHA = "d".repeat(40);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("analysis.jobs.enabled", () -> "false");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

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

	private TrackedRepository repository;

	private TrackedRepository otherRepository;

	private RepositorySnapshot snapshot;

	private RepositorySnapshot otherSnapshot;

	private AnalysisRun run;

	private int fingerprint;

	@BeforeEach
	void seed() {
		jdbc.update("DELETE FROM repository");
		repository = repositories.save(new TrackedRepository("octo", "demo", "https://github.com/octo/demo"));
		otherRepository = repositories.save(new TrackedRepository("octo", "other", "https://github.com/octo/other"));
		snapshot = newSnapshot(repository, SHA);
		otherSnapshot = newSnapshot(otherRepository, OTHER_SHA);
		run = completedRun(snapshot);
		AnalysisRun otherRun = completedRun(otherSnapshot);

		execution(run, "generic-structure-analyzer", AnalysisCategory.STRUCTURE, ExecutionStatus.COMPLETED, null);
		execution(run, "pmd-java", AnalysisCategory.CODE_QUALITY, ExecutionStatus.COMPLETED, null);
		execution(run, "semgrep", AnalysisCategory.SECURITY, ExecutionStatus.UNAVAILABLE, "semgrep is not installed");
		execution(run, "jacoco-ingestion", AnalysisCategory.TESTS, ExecutionStatus.FAILED, "report was unreadable");

		finding(run, AnalysisCategory.CODE_QUALITY, "GodClass", Severity.HIGH, "src/main/java/A.java", 10, 40, "backend");
		finding(run, AnalysisCategory.CODE_QUALITY, "TooManyMethods", Severity.LOW, "src/main/java/B.java", 5, 5, "backend");
		finding(run, AnalysisCategory.CODE_QUALITY, "UnusedImport", Severity.INFO, "web/src/x.ts", 1, 1, "frontend");
		finding(run, AnalysisCategory.CODE_QUALITY, "Critical", Severity.CRITICAL, "src/main/java/A.java", 99, 99, "backend");
		finding(run, AnalysisCategory.STRUCTURE, "structure-summary", Severity.INFO, null, null, null, null);
		// another snapshot's rows must never appear
		finding(otherRun, AnalysisCategory.CODE_QUALITY, "OtherRepoFinding", Severity.CRITICAL, "Z.java", 1, 1, null);

		dependency(run, "pom.xml", "org.springframework", "spring-core", "compile", VulnerabilityStatus.NOT_CHECKED, Ecosystem.MAVEN);
		dependency(run, "web/package.json", null, "react", "runtime", VulnerabilityStatus.VULNERABLE, Ecosystem.NPM);
		dependency(otherRun, "pom.xml", null, "leaked-dep", "compile", VulnerabilityStatus.NOT_CHECKED, Ecosystem.MAVEN);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	private RepositorySnapshot newSnapshot(TrackedRepository repo, String sha) {
		RepositorySnapshot s = snapshots.save(new RepositorySnapshot(repo, sha));
		s.setBranch("main");
		return snapshots.save(s);
	}

	private AnalysisRun completedRun(RepositorySnapshot s) {
		AnalysisRun r = runs.save(new AnalysisRun(s, RunStatus.COMPLETED_WITH_WARNINGS));
		return r;
	}

	private void execution(AnalysisRun r, String id, AnalysisCategory category, ExecutionStatus status, String error) {
		AnalyzerExecution e = new AnalyzerExecution(r, r.getSnapshot(), id, "1.0.0", category, status);
		e.setErrorSummary(error);
		executions.save(e);
	}

	private void finding(AnalysisRun r, AnalysisCategory category, String rule, Severity severity, String path,
			Integer start, Integer end, String module) {
		AnalysisFinding f = new AnalysisFinding(r.getSnapshot(), r, "analyzer-" + category, category, rule, severity,
				Confidence.LIKELY, "Title " + rule, AnalysisMethod.HEURISTIC, "fp-" + (fingerprint++));
		f.setFilePath(path);
		f.setStartLine(start);
		f.setEndLine(end);
		f.setModulePath(module);
		findings.save(f);
	}

	private void dependency(AnalysisRun r, String manifest, String namespace, String name, String scope,
			VulnerabilityStatus vulnerability, Ecosystem ecosystem) {
		DependencyRecord d = new DependencyRecord(r.getSnapshot(), r, "deps", ecosystem, manifest, name,
				VersionSource.DECLARED);
		d.setNamespace(namespace);
		d.setScope(scope);
		d.setVulnerabilityStatus(vulnerability);
		dependencies.save(d);
	}

	private String base(TrackedRepository repo) {
		return "/api/v1/repositories/" + repo.getId();
	}

	// ---- report ---------------------------------------------------------------------------------

	@Test
	void reportIsPopulatedAndScopedToTheCommit() throws Exception {
		mockMvc.perform(get(base(repository) + "/reports/" + snapshot.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repository.owner").value("octo"))
			.andExpect(jsonPath("$.snapshot.commitSha").value(SHA))
			.andExpect(jsonPath("$.snapshot.branch").value("main"))
			.andExpect(jsonPath("$.run.id").value(run.getId().toString()))
			.andExpect(jsonPath("$.run.status").value("COMPLETED_WITH_WARNINGS"))
			.andExpect(jsonPath("$.sections", hasSize(AnalysisCategory.values().length)))
			.andExpect(jsonPath("$.sections[?(@.category=='CODE_QUALITY')].status").value("COMPLETED"))
			.andExpect(jsonPath("$.sections[?(@.category=='CODE_QUALITY')].findingCountsBySeverity.HIGH").value(1))
			.andExpect(jsonPath("$.sections[?(@.category=='CODE_QUALITY')].findingCountsBySeverity.MEDIUM").value(0))
			.andExpect(jsonPath("$.structure.modules").isArray())
			.andExpect(jsonPath("$.technologies").isArray())
			.andExpect(jsonPath("$.capabilityMatrix").isArray());
	}

	@Test
	void notAnalyzedCategoriesHaveNullCountsNotZeros() throws Exception {
		mockMvc.perform(get(base(repository) + "/reports/" + snapshot.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sections[?(@.category=='SECURITY')].status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.sections[4].category").value("SECURITY"))
			.andExpect(jsonPath("$.sections[4].findingCountsBySeverity").value(nullValue()))
			.andExpect(jsonPath("$.sections[?(@.category=='TESTS')].status").value("FAILED"))
			.andExpect(jsonPath("$.sections[?(@.category=='DEPENDENCIES')].status").value("NOT_SUPPORTED"))
			.andExpect(jsonPath("$.sections[?(@.category=='DEPENDENCIES')].summary")
				.value("No analyzer exists for dependencies in this repository."));
	}

	@Test
	void failedAndUnavailableAnalyzersAppearInLimitationsWithReasons() throws Exception {
		mockMvc.perform(get(base(repository) + "/reports/" + snapshot.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.limitations[?(@.analyzerId=='semgrep')].reason").value("semgrep is not installed"))
			.andExpect(jsonPath("$.limitations[?(@.analyzerId=='jacoco-ingestion')].status").value("FAILED"))
			.andExpect(jsonPath("$.limitations[?(@.analyzerId=='jacoco-ingestion')].reason")
				.value("report was unreadable"));
	}

	@Test
	void aSnapshotFromAnotherRepositoryIs404() throws Exception {
		mockMvc.perform(get(base(repository) + "/reports/" + otherSnapshot.getId())).andExpect(status().isNotFound());
		mockMvc.perform(get(base(repository) + "/snapshots/" + otherSnapshot.getId() + "/findings"))
			.andExpect(status().isNotFound());
		mockMvc.perform(get(base(repository) + "/snapshots/" + otherSnapshot.getId() + "/dependencies"))
			.andExpect(status().isNotFound());
		mockMvc.perform(get(base(repository) + "/reports/" + UUID.randomUUID())).andExpect(status().isNotFound());
	}

	@Test
	void aRunOfAnotherSnapshotCannotBeRequested() throws Exception {
		AnalysisRun foreign = runs.findBySnapshotIdOrderByRequestedAtDesc(otherSnapshot.getId()).get(0);

		mockMvc.perform(get(base(repository) + "/reports/" + snapshot.getId() + "?runId=" + foreign.getId()))
			.andExpect(status().isNotFound());
	}

	@Test
	void aSnapshotWithoutACompletedRunIs404() throws Exception {
		RepositorySnapshot fresh = newSnapshot(repository, "e".repeat(40));
		runs.save(new AnalysisRun(fresh, RunStatus.ANALYZING));

		mockMvc.perform(get(base(repository) + "/reports/" + fresh.getId())).andExpect(status().isNotFound());
	}

	// ---- findings -------------------------------------------------------------------------------

	@Test
	void findingsAreScopedToTheSnapshotAndSortedBySeverityFirst() throws Exception {
		mockMvc.perform(get(base(repository) + "/snapshots/" + snapshot.getId() + "/findings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(5))
			.andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
			.andExpect(jsonPath("$.items[1].severity").value("HIGH"))
			.andExpect(jsonPath("$.items[*].ruleId").value(org.hamcrest.Matchers.not(hasItem("OtherRepoFinding"))));
	}

	@Test
	void findingsFilterBySeverityCategoryModuleAndFile() throws Exception {
		String url = base(repository) + "/snapshots/" + snapshot.getId() + "/findings";

		mockMvc.perform(get(url + "?severity=HIGH")).andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].ruleId").value("GodClass"));
		mockMvc.perform(get(url + "?category=STRUCTURE")).andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(get(url + "?module=frontend")).andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].ruleId").value("UnusedImport"));
		mockMvc.perform(get(url + "?file=src/main/java/A.java")).andExpect(jsonPath("$.totalElements").value(2));
		mockMvc.perform(get(url + "?file=src/main/java")).andExpect(jsonPath("$.totalElements").value(3));
		mockMvc.perform(get(url + "?category=CODE_QUALITY&severity=HIGH&module=backend"))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void findingsPaginate() throws Exception {
		String url = base(repository) + "/snapshots/" + snapshot.getId() + "/findings?size=2";

		mockMvc.perform(get(url + "&page=0")).andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.totalElements").value(5));
		mockMvc.perform(get(url + "&page=2")).andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	void findingsCarryAPermalinkAtTheExactCommit() throws Exception {
		String body = mockMvc
			.perform(get(base(repository) + "/snapshots/" + snapshot.getId() + "/findings?severity=HIGH"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat((String) JsonPath.read(body, "$.items[0].sourceUrl"))
			.isEqualTo("https://github.com/octo/demo/blob/" + SHA + "/src/main/java/A.java#L10-L40");
	}

	@Test
	void badSortAndPagingAre400() throws Exception {
		String url = base(repository) + "/snapshots/" + snapshot.getId() + "/findings";

		mockMvc.perform(get(url + "?sort=password")).andExpect(status().isBadRequest());
		mockMvc.perform(get(url + "?size=0")).andExpect(status().isBadRequest());
		mockMvc.perform(get(url + "?severity=SCARY")).andExpect(status().isBadRequest());
	}

	@Test
	void findingsCanBeSortedByFile() throws Exception {
		mockMvc.perform(get(base(repository) + "/snapshots/" + snapshot.getId() + "/findings?sort=file&category=CODE_QUALITY"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].filePath").value("src/main/java/A.java"))
			.andExpect(jsonPath("$.items[3].filePath").value("web/src/x.ts"));
	}

	// ---- dependencies ---------------------------------------------------------------------------

	@Test
	void dependenciesFilterAndNeverIncludeAnotherSnapshot() throws Exception {
		String url = base(repository) + "/snapshots/" + snapshot.getId() + "/dependencies";

		mockMvc.perform(get(url)).andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.not(hasItem("leaked-dep"))));
		mockMvc.perform(get(url + "?ecosystem=NPM")).andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].name").value("react"));
		mockMvc.perform(get(url + "?vulnerable=true")).andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(get(url + "?scope=compile")).andExpect(jsonPath("$.items[0].name").value("spring-core"));
	}

	// ---- empty states ---------------------------------------------------------------------------

	@Test
	void anEmptyRunStillRendersEveryCategoryAsAProperEmptyState() throws Exception {
		RepositorySnapshot empty = newSnapshot(repository, "f".repeat(40));
		completedRun(empty);

		mockMvc.perform(get(base(repository) + "/reports/" + empty.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sections", hasSize(AnalysisCategory.values().length)))
			.andExpect(jsonPath("$.sections[*].analyzers").isArray())
			.andExpect(jsonPath("$.sections[*].summary").isNotEmpty())
			.andExpect(jsonPath("$.technologies", hasSize(0)))
			.andExpect(jsonPath("$.structure.modules", hasSize(0)))
			.andExpect(jsonPath("$.structure.entryPoints", hasSize(0)))
			.andExpect(jsonPath("$.limitations", hasSize(0)));
		mockMvc.perform(get(base(repository) + "/snapshots/" + empty.getId() + "/findings"))
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

}
