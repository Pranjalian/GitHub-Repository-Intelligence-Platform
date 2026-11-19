package com.repointelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.support.EmbeddedPostgresConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Verifies the MVP 1 schema (V2, V3) on real PostgreSQL. Each test rolls back its changes. */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@Transactional
class SchemaMigrationTests {

	private static final String SHA_A = "a".repeat(40);

	private static final String SHA_B = "b".repeat(40);

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void allMvp1TablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

		assertThat(tables).contains("repository", "repository_snapshot", "analysis_run", "analyzer_execution",
				"file_record", "technology_finding", "analyzer_capability", "analysis_finding", "dependency",
				"module_record", "test_record", "coverage_record");
	}

	@Test
	void snapshotScopedTablesHaveSnapshotIdIndexes() {
		List<String> indexed = jdbc.queryForList("""
				SELECT DISTINCT tablename FROM pg_indexes
				WHERE schemaname = 'public' AND indexdef LIKE '%(snapshot_id%'
				""", String.class);

		assertThat(indexed).contains("analysis_run", "analyzer_execution", "file_record", "technology_finding",
				"analyzer_capability", "analysis_finding", "dependency", "module_record", "test_record",
				"coverage_record");
	}

	@Test
	void findingIndexesForFilteringAndDedupeExist() {
		List<String> indexes = jdbc.queryForList(
				"SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'analysis_finding'",
				String.class);

		assertThat(indexes).contains("idx_analysis_finding_snapshot_category_severity",
				"idx_analysis_finding_snapshot_fingerprint");
	}

	@Test
	void uuidPrimaryKeysAreGeneratedByTheDatabase() {
		UUID id = jdbc.queryForObject("""
				INSERT INTO repository (owner, name, canonical_url) VALUES ('o', 'r', 'https://github.com/o/r')
				RETURNING id
				""", UUID.class);

		assertThat(id).isNotNull();
		assertThat(jdbc.queryForObject("SELECT host FROM repository WHERE id = ?", String.class, id))
			.isEqualTo("github.com");
	}

	@Test
	void repositoryIdentityIsUniqueCaseInsensitively() {
		insertRepository("Spring-Projects", "Spring-Petclinic");

		assertThatThrownBy(() -> insertRepository("spring-projects", "spring-petclinic"))
			.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void sameRepositoryNameOnDifferentHostsIsAllowed() {
		insertRepository("o", "r");
		jdbc.update("INSERT INTO repository (host, owner, name, canonical_url) VALUES ('gitlab.com', 'o', 'r', 'x')");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM repository", Integer.class)).isEqualTo(2);
	}

	@Test
	void snapshotIsUniquePerRepositoryAndCommit() {
		UUID repo = insertRepository("o", "r");
		insertSnapshot(repo, SHA_A);

		assertThatThrownBy(() -> insertSnapshot(repo, SHA_A)).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void sameCommitMayExistInDifferentRepositoriesAndNewCommitsGetNewSnapshots() {
		UUID repo1 = insertRepository("o", "r1");
		UUID repo2 = insertRepository("o", "r2");

		insertSnapshot(repo1, SHA_A);
		insertSnapshot(repo2, SHA_A);
		insertSnapshot(repo1, SHA_B);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM repository_snapshot", Integer.class)).isEqualTo(3);
	}

	@Test
	void commitShaMustBeAFullLowercaseSha() {
		UUID repo = insertRepository("o", "r");

		assertThatThrownBy(() -> insertSnapshot(repo, "main")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void filePathIsUniquePerSnapshot() {
		UUID snapshot = insertSnapshot(insertRepository("o", "r"), SHA_A);
		insertFile(snapshot, "src/Main.java");

		assertThatThrownBy(() -> insertFile(snapshot, "src/Main.java")).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void samePathMayExistInDifferentSnapshots() {
		UUID repo = insertRepository("o", "r");
		insertFile(insertSnapshot(repo, SHA_A), "src/Main.java");
		insertFile(insertSnapshot(repo, SHA_B), "src/Main.java");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM file_record", Integer.class)).isEqualTo(2);
	}

	@Test
	void jsonbColumnsRoundTrip() {
		UUID snapshot = insertSnapshot(insertRepository("o", "r"), SHA_A);
		UUID run = insertRun(snapshot);

		jdbc.update("""
				INSERT INTO technology_finding (snapshot_id, run_id, technology, kind, confidence, evidence, detector_id)
				VALUES (?, ?, 'Maven', 'BUILD_TOOL', 'DETECTED', '[{"path":"pom.xml","signal":"manifest"}]'::jsonb, 'manifest')
				""", snapshot, run);

		String path = jdbc.queryForObject("SELECT evidence -> 0 ->> 'path' FROM technology_finding", String.class);
		assertThat(path).isEqualTo("pom.xml");
	}

	@Test
	void deletingASnapshotRemovesAllDerivedData() {
		UUID snapshot = insertSnapshot(insertRepository("o", "r"), SHA_A);
		UUID run = insertRun(snapshot);
		insertFile(snapshot, "pom.xml");
		jdbc.update("""
				INSERT INTO analysis_finding (snapshot_id, run_id, analyzer_id, category, rule_id, severity, confidence,
				    title, analysis_method, fingerprint)
				VALUES (?, ?, 'secrets', 'SECURITY', 'aws-key', 'HIGH', 'LIKELY', 't', 'DETERMINISTIC_SCANNER', 'fp')
				""", snapshot, run);

		jdbc.update("DELETE FROM repository_snapshot WHERE id = ?", snapshot);

		for (String table : List.of("analysis_run", "file_record", "analysis_finding")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
		}
	}

	@Test
	void derivedRowsRequireAnExistingSnapshot() {
		assertThatThrownBy(() -> insertRun(UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertRepository(String owner, String name) {
		return jdbc.queryForObject("""
				INSERT INTO repository (owner, name, canonical_url) VALUES (?, ?, ?) RETURNING id
				""", UUID.class, owner, name, "https://github.com/" + owner + "/" + name);
	}

	private UUID insertSnapshot(UUID repositoryId, String sha) {
		return jdbc.queryForObject("""
				INSERT INTO repository_snapshot (repository_id, commit_sha) VALUES (?, ?) RETURNING id
				""", UUID.class, repositoryId, sha);
	}

	private UUID insertRun(UUID snapshotId) {
		return jdbc.queryForObject("""
				INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'QUEUED') RETURNING id
				""", UUID.class, snapshotId);
	}

	private void insertFile(UUID snapshotId, String path) {
		jdbc.update("INSERT INTO file_record (snapshot_id, path, file_type, size_bytes) VALUES (?, ?, 'SOURCE', 10)",
				snapshotId, path);
	}

}
