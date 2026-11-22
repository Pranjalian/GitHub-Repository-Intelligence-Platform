package com.repointelligence.rag.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.rag.retrieval.ChunkFilter;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * pgvector against a real PostgreSQL with the extension (the embedded binaries of the other tests have none).
 * Opt-in, like the Semgrep CLI tests: set {@code PGVECTOR_TEST_DB_URL} (and {@code PGVECTOR_TEST_DB_USERNAME},
 * {@code PGVECTOR_TEST_DB_PASSWORD}) to an empty, throw-away database, e.g. one created in the Docker Compose
 * database: {@code CREATE DATABASE repointel_vector_test}. The migrations are applied to it, and the pgvector
 * ranking must equal the in-process ranking, both scoped to one snapshot.
 */
@EnabledIfEnvironmentVariable(named = "PGVECTOR_TEST_DB_URL", matches = "jdbc:postgresql:.+")
class PgVectorStoreTests {

	static JdbcTemplate jdbc;

	static UUID repository;

	static UUID snapshot;

	static UUID otherSnapshot;

	@BeforeAll
	static void migrate() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(System.getenv("PGVECTOR_TEST_DB_URL"),
				System.getenv().getOrDefault("PGVECTOR_TEST_DB_USERNAME", "repointel"),
				System.getenv().getOrDefault("PGVECTOR_TEST_DB_PASSWORD", "repointel_dev"));
		Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
		Flyway.configure().dataSource(dataSource).load().migrate();
		jdbc = new JdbcTemplate(dataSource);
		repository = jdbc.queryForObject(
				"INSERT INTO repository (owner, name, canonical_url) VALUES ('o', 'r', 'https://github.com/o/r') RETURNING id",
				UUID.class);
		snapshot = insertSnapshot("1");
		otherSnapshot = insertSnapshot("2");
		Random random = new Random(42);
		for (int i = 0; i < 40; i++) {
			insertChunk(i % 4 == 0 ? otherSnapshot : snapshot, "File" + i + ".java", random);
		}
	}

	static UUID insertSnapshot(String digit) {
		return jdbc.queryForObject("INSERT INTO repository_snapshot (repository_id, commit_sha) VALUES (?, ?) RETURNING id",
				UUID.class, repository, digit.repeat(40));
	}

	static void insertChunk(UUID snapshotId, String path, Random random) {
		String hash = String.format("%064x", random.nextLong() & Long.MAX_VALUE);
		jdbc.update("""
				INSERT INTO code_chunk (snapshot_id, repository_id, file_path, chunk_type, start_line, end_line, content, content_hash)
				VALUES (?, ?, ?, 'LINE_WINDOW', 1, 10, 'x', ?)
				""", snapshotId, repository, path, hash);
		Float[] vector = new Float[16];
		for (int d = 0; d < vector.length; d++) {
			vector[d] = (float) random.nextGaussian();
		}
		jdbc.update("""
				INSERT INTO chunk_embedding (content_hash, embedding_model, embedding_model_version, dimensions, embedding)
				VALUES (?, 'm', 'v', 16, ?::real[])
				""", hash, "{" + String.join(",", java.util.Arrays.stream(vector).map(String::valueOf).toList()) + "}");
	}

	@Test
	void theExtensionIsInstalledByTheMigration() {
		assertThat(VectorStoreConfig.pgvectorInstalled(jdbc)).isTrue();
	}

	@Test
	void pgvectorRanksExactlyLikeTheInProcessStoreAndStaysInTheSnapshot() {
		float[] query = new float[16];
		Random random = new Random(7);
		for (int d = 0; d < query.length; d++) {
			query[d] = (float) random.nextGaussian();
		}

		List<VectorStore.VectorHit> pg = new PgVectorStore(jdbc).nearest(snapshot, repository, "m", "v", query,
				ChunkFilter.NONE, 10).hits();
		List<VectorStore.VectorHit> local = new InProcessVectorStore(jdbc, 1000).nearest(snapshot, repository, "m", "v", query,
				ChunkFilter.NONE, 10).hits();

		assertThat(pg).hasSize(10);
		assertThat(pg).extracting(VectorStore.VectorHit::chunkId).containsExactlyElementsOf(
				local.stream().map(VectorStore.VectorHit::chunkId).toList());
		for (int i = 0; i < pg.size(); i++) {
			assertThat(pg.get(i).similarity()).isCloseTo(local.get(i).similarity(), org.assertj.core.data.Offset.offset(1e-4));
		}
		List<UUID> snapshots = pg.stream()
			.map(hit -> jdbc.queryForObject("SELECT snapshot_id FROM code_chunk WHERE id = ?", UUID.class, hit.chunkId()))
			.distinct()
			.toList();
		assertThat(snapshots).containsExactly(snapshot);
		// another model version has no vectors: nothing is compared across models
		assertThat(new PgVectorStore(jdbc).nearest(snapshot, repository, "m", "other", query, ChunkFilter.NONE, 10).hits())
			.isEmpty();
	}

}
