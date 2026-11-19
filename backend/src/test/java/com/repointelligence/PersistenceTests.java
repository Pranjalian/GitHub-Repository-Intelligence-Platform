package com.repointelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.AnalyzerCapability;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.domain.CoverageRecord;
import com.repointelligence.analysis.domain.CoverageScope;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.MappingMethod;
import com.repointelligence.analysis.domain.ModuleRecord;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.domain.TestRecord;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerCapabilityRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.infrastructure.persistence.CoverageRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.ModuleRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.TestRecordRepository;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.IngestionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.FileRecord;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.FileRecordRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.support.EmbeddedPostgresConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entity mapping and repository query tests on real PostgreSQL. Booting the context also runs
 * Hibernate's schema validation ({@code ddl-auto=validate}) against the Flyway-created schema.
 * Each test rolls back; {@code flushAndClear()} forces a real database round trip.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@Transactional
class PersistenceTests {

	private static final String SHA_A = "a".repeat(40);

	private static final String SHA_B = "b".repeat(40);

	private static final Set<RunStatus> COMPLETED = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_WARNINGS);

	private static final Set<RunStatus> IN_FLIGHT = Set.of(RunStatus.QUEUED, RunStatus.INGESTING, RunStatus.ANALYZING);

	@Autowired
	private EntityManager em;

	@Autowired
	private Environment environment;

	@Autowired
	private TrackedRepositoryRepository repositories;

	@Autowired
	private RepositorySnapshotRepository snapshots;

	@Autowired
	private FileRecordRepository files;

	@Autowired
	private AnalysisRunRepository runs;

	@Autowired
	private AnalyzerExecutionRepository executions;

	@Autowired
	private TechnologyFindingRepository technologies;

	@Autowired
	private AnalyzerCapabilityRepository capabilities;

	@Autowired
	private AnalysisFindingRepository findings;

	@Autowired
	private DependencyRecordRepository dependencies;

	@Autowired
	private ModuleRecordRepository modules;

	@Autowired
	private TestRecordRepository tests;

	@Autowired
	private CoverageRecordRepository coverage;

	// ---- entity round trips -------------------------------------------------------------------

	@Test
	void trackedRepositoryRoundTrip() {
		TrackedRepository saved = newRepository("Spring-Projects", "Spring-Petclinic");
		saved.setDefaultBranch("main");
		saved.setVisibility("public");
		saved.setGithubRepoId(1234567890123L);
		flushAndClear();

		TrackedRepository found = repositories.findById(saved.getId()).orElseThrow();

		assertThat(found.getHost()).isEqualTo("github.com");
		assertThat(found.getOwner()).isEqualTo("Spring-Projects");
		assertThat(found.getCanonicalUrl()).isEqualTo("https://github.com/Spring-Projects/Spring-Petclinic");
		assertThat(found.getDefaultBranch()).isEqualTo("main");
		assertThat(found.getGithubRepoId()).isEqualTo(1234567890123L);
		assertThat(found.getCreatedAt()).isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
	}

	@Test
	void snapshotRoundTrip() {
		TrackedRepository repo = newRepository("o", "r");
		RepositorySnapshot snapshot = new RepositorySnapshot(repo, SHA_A);
		snapshot.setBranch("main");
		snapshot.setIngestionStatus(IngestionStatus.INGESTED);
		snapshot.setFileCount(42);
		snapshot.setTotalBytes(123_456_789_012L);
		snapshot.setIngestedAt(Instant.parse("2026-11-19T10:00:00Z"));
		snapshots.save(snapshot);
		flushAndClear();

		RepositorySnapshot found = snapshots.findById(snapshot.getId()).orElseThrow();

		assertThat(found.getCommitSha()).isEqualTo(SHA_A);
		assertThat(found.getIngestionStatus()).isEqualTo(IngestionStatus.INGESTED);
		assertThat(found.getFileCount()).isEqualTo(42);
		assertThat(found.getTotalBytes()).isEqualTo(123_456_789_012L);
		assertThat(found.getIngestedAt()).isEqualTo(Instant.parse("2026-11-19T10:00:00Z"));
		assertThat(found.getRepository().getId()).isEqualTo(repo.getId());
	}

	@Test
	void newSnapshotDefaultsToPending() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		flushAndClear();

		assertThat(snapshots.findById(snapshot.getId()).orElseThrow().getIngestionStatus())
			.isEqualTo(IngestionStatus.PENDING);
	}

	@Test
	void fileRecordRoundTrip() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		FileRecord file = new FileRecord(snapshot, "src/main/java/App.java", FileType.SOURCE, 2048L);
		file.setLanguage("Java");
		file.setContentHash("f".repeat(64));
		file.setGitBlobSha("e".repeat(40));
		files.save(file);
		FileRecord binary = new FileRecord(snapshot, "logo.png", FileType.OTHER, 10L);
		binary.setBinary(true);
		binary.setSkippedReason("binary file");
		files.save(binary);
		flushAndClear();

		FileRecord found = files.findById(file.getId()).orElseThrow();
		FileRecord foundBinary = files.findById(binary.getId()).orElseThrow();

		assertThat(found.getFileType()).isEqualTo(FileType.SOURCE);
		assertThat(found.getLanguage()).isEqualTo("Java");
		assertThat(found.getContentHash()).isEqualTo("f".repeat(64));
		assertThat(found.isBinary()).isFalse();
		assertThat(foundBinary.isBinary()).isTrue();
		assertThat(foundBinary.getSkippedReason()).isEqualTo("binary file");
	}

	@Test
	void analysisRunRoundTrip() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = new AnalysisRun(snapshot, RunStatus.QUEUED);
		run.setForceRescan(true);
		run.setAnalyzerSetHash("abc123");
		run.setRetryAfter(Instant.parse("2026-11-19T11:00:00Z"));
		runs.save(run);
		flushAndClear();

		AnalysisRun found = runs.findById(run.getId()).orElseThrow();

		assertThat(found.getStatus()).isEqualTo(RunStatus.QUEUED);
		assertThat(found.isForceRescan()).isTrue();
		assertThat(found.getAnalyzerSetHash()).isEqualTo("abc123");
		assertThat(found.getRetryAfter()).isEqualTo(Instant.parse("2026-11-19T11:00:00Z"));
		assertThat(found.getRequestedAt()).isNotNull();
		assertThat(found.getSnapshot().getId()).isEqualTo(snapshot.getId());
	}

	@Test
	void analyzerExecutionRoundTripWithJsonb() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.ANALYZING);
		AnalyzerExecution execution = new AnalyzerExecution(run, snapshot, "pmd-java", "1.0.0",
				AnalysisCategory.CODE_QUALITY, ExecutionStatus.COMPLETED_WITH_WARNINGS);
		execution.setDurationMs(1532L);
		execution.setConfigSummary(Map.of("ruleset", "repointel-java-quality", "maxFiles", 500));
		execution.setLimitations(List.of("no auxclasspath", "type resolution limited"));
		execution.setErrorSummary("1 file failed to parse");
		executions.save(execution);
		flushAndClear();

		AnalyzerExecution found = executions.findByRunId(run.getId()).get(0);

		assertThat(found.getAnalyzerId()).isEqualTo("pmd-java");
		assertThat(found.getCategory()).isEqualTo(AnalysisCategory.CODE_QUALITY);
		assertThat(found.getStatus()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(found.getDurationMs()).isEqualTo(1532L);
		assertThat(found.getConfigSummary()).containsEntry("ruleset", "repointel-java-quality")
			.containsEntry("maxFiles", 500);
		assertThat(found.getLimitations()).containsExactly("no auxclasspath", "type resolution limited");
	}

	@Test
	void technologyFindingRoundTripWithTypedEvidence() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.ANALYZING);
		TechnologyFinding finding = new TechnologyFinding(snapshot, run, "Java", TechnologyKind.LANGUAGE,
				Confidence.LIKELY, "language-distribution");
		finding.setSharePercent(new BigDecimal("87.50"));
		finding.setVersion("25");
		finding.setEvidence(List.of(new Evidence("pom.xml", 12, "maven.compiler.release"),
				new Evidence("src/Main.java", null, "extension")));
		technologies.save(finding);
		flushAndClear();

		TechnologyFinding found = technologies.findBySnapshotIdAndRunId(snapshot.getId(), run.getId()).get(0);

		assertThat(found.getKind()).isEqualTo(TechnologyKind.LANGUAGE);
		assertThat(found.getConfidence()).isEqualTo(Confidence.LIKELY);
		assertThat(found.getSharePercent()).isEqualByComparingTo("87.50");
		assertThat(found.getEvidence()).containsExactly(new Evidence("pom.xml", 12, "maven.compiler.release"),
				new Evidence("src/Main.java", null, "extension"));
	}

	@Test
	void analyzerCapabilityRoundTripAllowsNoAnalyzer() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.ANALYZING);
		AnalyzerCapability capability = new AnalyzerCapability(snapshot, run, "TypeScript", AnalysisCategory.CODE_QUALITY,
				CapabilityLevel.UNSUPPORTED, ExecutionStatus.NOT_SUPPORTED);
		capability.setLimitationReason("no analyzer pack for TypeScript");
		capabilities.save(capability);
		flushAndClear();

		AnalyzerCapability found = capabilities.findBySnapshotIdAndRunId(snapshot.getId(), run.getId()).get(0);

		assertThat(found.getAnalyzerId()).isNull();
		assertThat(found.getCapabilityLevel()).isEqualTo(CapabilityLevel.UNSUPPORTED);
		assertThat(found.getExecutionStatus()).isEqualTo(ExecutionStatus.NOT_SUPPORTED);
		assertThat(found.getLimitationReason()).isEqualTo("no analyzer pack for TypeScript");
	}

	@Test
	void analysisFindingRoundTrip() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.ANALYZING);
		AnalysisFinding finding = newFinding(snapshot, run, AnalysisCategory.SECURITY, Severity.HIGH, "aws-access-key",
				"fp-1");
		finding.setFilePath("src/main/resources/application.yml");
		finding.setStartLine(12);
		finding.setEndLine(12);
		finding.setDescription("Possible AWS access key");
		finding.setEvidence(Map.of("excerpt", "aws.key: ***REDACTED***", "entropy", 4.2));
		finding.setSuggestedNextStep("Rotate the key and remove it from source control");
		findings.save(finding);
		flushAndClear();

		AnalysisFinding found = findings.findById(finding.getId()).orElseThrow();

		assertThat(found.getSeverity()).isEqualTo(Severity.HIGH);
		assertThat(found.getAnalysisMethod()).isEqualTo(AnalysisMethod.DETERMINISTIC_SCANNER);
		assertThat(found.getConfidence()).isEqualTo(Confidence.LIKELY);
		assertThat(found.getStartLine()).isEqualTo(12);
		assertThat(found.getEvidence()).containsEntry("excerpt", "aws.key: ***REDACTED***");
		assertThat(found.getSuggestedNextStep()).startsWith("Rotate");
	}

	@Test
	void dependencyRecordRoundTrip() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.ANALYZING);
		DependencyRecord defaults = new DependencyRecord(snapshot, run, "maven-dependencies", Ecosystem.MAVEN, "pom.xml",
				"spring-boot-starter-web", VersionSource.MANAGED_REMOTE_UNRESOLVED);
		defaults.setNamespace("org.springframework.boot");
		defaults.setScope("compile");
		dependencies.save(defaults);
		DependencyRecord vulnerable = new DependencyRecord(snapshot, run, "maven-dependencies", Ecosystem.MAVEN,
				"pom.xml", "log4j-core", VersionSource.DECLARED);
		vulnerable.setDeclaredVersion("2.14.0");
		vulnerable.setResolvedVersion("2.14.0");
		vulnerable.setManagementEntry(true);
		vulnerable.setDirect(false);
		vulnerable.setVulnerabilityStatus(VulnerabilityStatus.VULNERABLE);
		vulnerable.setVulnerabilityIds(List.of("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q"));
		dependencies.save(vulnerable);
		flushAndClear();

		DependencyRecord foundDefaults = dependencies.findById(defaults.getId()).orElseThrow();
		DependencyRecord foundVulnerable = dependencies.findById(vulnerable.getId()).orElseThrow();

		assertThat(foundDefaults.isDirect()).isTrue();
		assertThat(foundDefaults.isManagementEntry()).isFalse();
		assertThat(foundDefaults.getVersionSource()).isEqualTo(VersionSource.MANAGED_REMOTE_UNRESOLVED);
		assertThat(foundDefaults.getVulnerabilityStatus()).isEqualTo(VulnerabilityStatus.NOT_CHECKED);
		assertThat(foundDefaults.getVulnerabilityIds()).isNull();
		assertThat(foundVulnerable.isDirect()).isFalse();
		assertThat(foundVulnerable.isManagementEntry()).isTrue();
		assertThat(foundVulnerable.getVulnerabilityStatus()).isEqualTo(VulnerabilityStatus.VULNERABLE);
		assertThat(foundVulnerable.getVulnerabilityIds()).containsExactly("CVE-2021-44228", "GHSA-jfh8-c2jp-5v3q");
	}

	@Test
	void moduleTestAndCoverageRecordsRoundTrip() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.ANALYZING);
		ModuleRecord module = new ModuleRecord(snapshot, run, "api", "modules/api");
		module.setBuildFilePath("modules/api/pom.xml");
		module.setEcosystem(Ecosystem.MAVEN);
		module.setEvidence(List.of(new Evidence("modules/api/pom.xml", null, "build manifest")));
		modules.save(module);
		TestRecord test = new TestRecord(snapshot, run, "src/test/java/com/acme/OrderServiceTest.java");
		test.setTestSymbol("com.acme.OrderServiceTest");
		test.setTestMethodCount(7);
		test.setFramework("JUnit 5");
		test.setProductionSymbol("com.acme.OrderService");
		test.setMappingMethod(MappingMethod.NAMING_CONVENTION);
		test.setConfidence(Confidence.POSSIBLE);
		tests.save(test);
		CoverageRecord record = new CoverageRecord(snapshot, run, "target/site/jacoco/jacoco.xml", CoverageScope.CLASS,
				"com.acme.OrderService");
		record.setLineCovered(80);
		record.setLineMissed(20);
		coverage.save(record);
		flushAndClear();

		ModuleRecord foundModule = modules.findBySnapshotIdAndRunId(snapshot.getId(), run.getId()).get(0);
		TestRecord foundTest = tests.findBySnapshotIdAndRunId(snapshot.getId(), run.getId()).get(0);
		CoverageRecord foundCoverage = coverage.findBySnapshotIdAndRunId(snapshot.getId(), run.getId()).get(0);

		assertThat(foundModule.getEcosystem()).isEqualTo(Ecosystem.MAVEN);
		assertThat(foundModule.getEvidence()).hasSize(1);
		assertThat(foundTest.getMappingMethod()).isEqualTo(MappingMethod.NAMING_CONVENTION);
		assertThat(foundTest.getTestMethodCount()).isEqualTo(7);
		assertThat(foundCoverage.getScope()).isEqualTo(CoverageScope.CLASS);
		assertThat(foundCoverage.getLineCovered()).isEqualTo(80);
		assertThat(foundCoverage.getBranchCovered()).isNull();
	}

	// ---- repository queries -------------------------------------------------------------------

	@Test
	void findsRepositoryCaseInsensitively() {
		TrackedRepository repo = newRepository("Spring-Projects", "Spring-Petclinic");
		flushAndClear();

		assertThat(repositories.findByHostAndOwnerIgnoreCaseAndNameIgnoreCase("github.com", "spring-projects",
				"spring-petclinic"))
			.map(TrackedRepository::getId)
			.contains(repo.getId());
		assertThat(repositories.findByHostAndOwnerIgnoreCaseAndNameIgnoreCase("github.com", "other", "repo")).isEmpty();
	}

	@Test
	void findsSnapshotByRepositoryAndCommit() {
		TrackedRepository repo = newRepository("o", "r");
		RepositorySnapshot snapshot = newSnapshot(repo, SHA_A);
		flushAndClear();

		assertThat(snapshots.findByRepositoryIdAndCommitSha(repo.getId(), SHA_A)).map(RepositorySnapshot::getId)
			.contains(snapshot.getId());
		assertThat(snapshots.findByRepositoryIdAndCommitSha(repo.getId(), SHA_B)).isEmpty();
	}

	@Test
	void snapshotLookupIsScopedToItsRepository() {
		TrackedRepository repo = newRepository("o", "r");
		TrackedRepository other = newRepository("o", "other");
		RepositorySnapshot snapshot = newSnapshot(repo, SHA_A);
		flushAndClear();

		assertThat(snapshots.findByIdAndRepositoryId(snapshot.getId(), repo.getId())).isPresent();
		assertThat(snapshots.findByIdAndRepositoryId(snapshot.getId(), other.getId())).isEmpty();
	}

	@Test
	void listsSnapshotsNewestFirstWithPaging() throws InterruptedException {
		TrackedRepository repo = newRepository("o", "r");
		RepositorySnapshot first = newSnapshot(repo, SHA_A);
		em.flush();
		Thread.sleep(5);
		RepositorySnapshot second = newSnapshot(repo, SHA_B);
		flushAndClear();

		var page = snapshots.findByRepositoryIdOrderByCreatedAtDesc(repo.getId(), PageRequest.of(0, 1));

		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).extracting(RepositorySnapshot::getId).containsExactly(second.getId());
		assertThat(snapshots.findFirstByRepositoryIdOrderByCreatedAtDesc(repo.getId()))
			.map(RepositorySnapshot::getId)
			.contains(second.getId());
		assertThat(first.getId()).isNotEqualTo(second.getId());
	}

	@Test
	void latestCompletedRunIgnoresInFlightAndFailedRuns() throws InterruptedException {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun oldCompleted = newRun(snapshot, RunStatus.COMPLETED);
		em.flush();
		Thread.sleep(5);
		AnalysisRun newerWithWarnings = newRun(snapshot, RunStatus.COMPLETED_WITH_WARNINGS);
		em.flush();
		Thread.sleep(5);
		newRun(snapshot, RunStatus.FAILED);
		em.flush();
		Thread.sleep(5);
		AnalysisRun newestQueued = newRun(snapshot, RunStatus.QUEUED);
		flushAndClear();

		assertThat(runs.findTopBySnapshotIdAndStatusInOrderByRequestedAtDesc(snapshot.getId(), COMPLETED))
			.map(AnalysisRun::getId)
			.contains(newerWithWarnings.getId());
		assertThat(runs.findTopBySnapshotIdOrderByRequestedAtDesc(snapshot.getId()))
			.map(AnalysisRun::getId)
			.contains(newestQueued.getId());
		assertThat(runs.findBySnapshotIdOrderByRequestedAtDesc(snapshot.getId())).hasSize(4);
		assertThat(oldCompleted.getId()).isNotEqualTo(newerWithWarnings.getId());
	}

	@Test
	void noCompletedRunYieldsEmpty() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		newRun(snapshot, RunStatus.QUEUED);
		flushAndClear();

		assertThat(runs.findTopBySnapshotIdAndStatusInOrderByRequestedAtDesc(snapshot.getId(), COMPLETED)).isEmpty();
	}

	@Test
	void inFlightRunQueriesSupportRecoveryAndJobCap() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		long before = runs.countByStatusIn(IN_FLIGHT);
		newRun(snapshot, RunStatus.QUEUED);
		newRun(snapshot, RunStatus.ANALYZING);
		newRun(snapshot, RunStatus.COMPLETED);
		flushAndClear();

		assertThat(runs.countByStatusIn(IN_FLIGHT)).isEqualTo(before + 2);
		assertThat(runs.findByStatusIn(IN_FLIGHT)).extracting(AnalysisRun::getStatus)
			.contains(RunStatus.QUEUED, RunStatus.ANALYZING)
			.doesNotContain(RunStatus.COMPLETED);
		assertThat(runs.findBySnapshotIdAndStatusIn(snapshot.getId(), IN_FLIGHT)).hasSize(2);
	}

	// ---- snapshot scoping ---------------------------------------------------------------------

	@Test
	void queriesNeverReturnAnotherSnapshotsRows() {
		TrackedRepository repo = newRepository("o", "r");
		RepositorySnapshot snapshotA = newSnapshot(repo, SHA_A);
		RepositorySnapshot snapshotB = newSnapshot(repo, SHA_B);
		AnalysisRun runA = newRun(snapshotA, RunStatus.COMPLETED);
		AnalysisRun runB = newRun(snapshotB, RunStatus.COMPLETED);
		findings.save(newFinding(snapshotA, runA, AnalysisCategory.SECURITY, Severity.HIGH, "rule-a", "fp-a"));
		findings.save(newFinding(snapshotB, runB, AnalysisCategory.SECURITY, Severity.LOW, "rule-b", "fp-b"));
		files.save(new FileRecord(snapshotA, "a.txt", FileType.OTHER, 1));
		files.save(new FileRecord(snapshotB, "b.txt", FileType.OTHER, 1));
		flushAndClear();

		assertThat(findings.findBySnapshotIdAndRunId(snapshotA.getId(), runA.getId())).extracting(AnalysisFinding::getRuleId)
			.containsExactly("rule-a");
		assertThat(findings.findBySnapshotIdAndRunId(snapshotA.getId(), runB.getId())).isEmpty();
		assertThat(files.findBySnapshotId(snapshotB.getId())).extracting(FileRecord::getPath).containsExactly("b.txt");
		assertThat(files.countBySnapshotId(snapshotA.getId())).isEqualTo(1);
		assertThat(files.existsBySnapshotId(snapshotA.getId())).isTrue();
	}

	@Test
	void findingFiltersAndSeverityCounts() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		AnalysisRun run = newRun(snapshot, RunStatus.COMPLETED);
		findings.save(newFinding(snapshot, run, AnalysisCategory.SECURITY, Severity.HIGH, "r1", "f1"));
		findings.save(newFinding(snapshot, run, AnalysisCategory.SECURITY, Severity.HIGH, "r2", "f2"));
		findings.save(newFinding(snapshot, run, AnalysisCategory.SECURITY, Severity.LOW, "r3", "f3"));
		findings.save(newFinding(snapshot, run, AnalysisCategory.CODE_QUALITY, Severity.MEDIUM, "r4", "f4"));
		flushAndClear();

		var security = findings.findBySnapshotIdAndRunIdAndCategory(snapshot.getId(), run.getId(),
				AnalysisCategory.SECURITY, PageRequest.of(0, 2));
		var high = findings.findBySnapshotIdAndRunIdAndSeverity(snapshot.getId(), run.getId(), Severity.HIGH,
				PageRequest.of(0, 10));
		var counts = findings.countByCategoryAndSeverity(snapshot.getId(), run.getId());

		assertThat(security.getTotalElements()).isEqualTo(3);
		assertThat(security.getContent()).hasSize(2);
		assertThat(high.getTotalElements()).isEqualTo(2);
		assertThat(counts).hasSize(3);
		assertThat(counts).anySatisfy(count -> {
			assertThat(count.getCategory()).isEqualTo(AnalysisCategory.SECURITY);
			assertThat(count.getSeverity()).isEqualTo(Severity.HIGH);
			assertThat(count.getTotal()).isEqualTo(2);
		});
	}

	// ---- constraints and bulk insert ----------------------------------------------------------

	@Test
	void duplicateFilePathInASnapshotIsRejected() {
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);
		files.save(new FileRecord(snapshot, "a.txt", FileType.OTHER, 1));
		files.save(new FileRecord(snapshot, "a.txt", FileType.OTHER, 1));

		assertThatThrownBy(() -> files.flush()).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_file_record_snapshot_path");
	}

	@Test
	void bulkInsertOfManyFileRecordsWorksWithBatching() {
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("50");
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.order_inserts")).isEqualTo("true");
		RepositorySnapshot snapshot = newSnapshot(newRepository("o", "r"), SHA_A);

		for (int i = 0; i < 520; i++) {
			files.save(new FileRecord(snapshot, "src/File" + i + ".java", FileType.SOURCE, i));
		}
		flushAndClear();

		assertThat(files.countBySnapshotId(snapshot.getId())).isEqualTo(520);
	}

	@Test
	void entityEqualityIsBasedOnId() {
		TrackedRepository repo = newRepository("o", "r");
		flushAndClear();

		TrackedRepository first = repositories.findById(repo.getId()).orElseThrow();
		em.clear();
		TrackedRepository second = repositories.findById(repo.getId()).orElseThrow();

		assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
		assertThat(first).isNotEqualTo(new TrackedRepository("x", "y", "z"));
		assertThat(first.getId()).isInstanceOf(UUID.class);
	}

	// ---- helpers ------------------------------------------------------------------------------

	private void flushAndClear() {
		em.flush();
		em.clear();
	}

	private TrackedRepository newRepository(String owner, String name) {
		return repositories.save(new TrackedRepository(owner, name, "https://github.com/" + owner + "/" + name));
	}

	private RepositorySnapshot newSnapshot(TrackedRepository repository, String sha) {
		return snapshots.save(new RepositorySnapshot(repository, sha));
	}

	private AnalysisRun newRun(RepositorySnapshot snapshot, RunStatus status) {
		return runs.save(new AnalysisRun(snapshot, status));
	}

	private AnalysisFinding newFinding(RepositorySnapshot snapshot, AnalysisRun run, AnalysisCategory category,
			Severity severity, String ruleId, String fingerprint) {
		return new AnalysisFinding(snapshot, run, "analyzer-1", category, ruleId, severity, Confidence.LIKELY,
				"Title " + ruleId, AnalysisMethod.DETERMINISTIC_SCANNER, fingerprint);
	}

}
