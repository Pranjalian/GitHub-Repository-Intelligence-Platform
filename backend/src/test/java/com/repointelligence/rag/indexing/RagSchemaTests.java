package com.repointelligence.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.support.EmbeddedPostgresConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The V7 RAG schema on real PostgreSQL without pgvector (the embedded binaries have none). Each test rolls back. */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@Transactional
class RagSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	private UUID[] snapshot() {
		UUID repository = jdbc.queryForObject(
				"INSERT INTO repository (owner, name, canonical_url) VALUES ('o', 'r', 'https://github.com/o/r') RETURNING id",
				UUID.class);
		UUID snapshot = jdbc.queryForObject(
				"INSERT INTO repository_snapshot (repository_id, commit_sha) VALUES (?, ?) RETURNING id", UUID.class,
				repository, "c".repeat(40));
		return new UUID[] { repository, snapshot };
	}

	@Test
	void theMigrationSucceedsWithoutPgvectorAndTheVectorStoreFallsBack() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class)).isZero();
		assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
				String.class)).contains("code_chunk", "chunk_embedding", "snapshot_index");
	}

	@Test
	void theKeywordIndexIsGeneratedWithWholeIdentifiersAndTheirParts() {
		UUID[] ids = snapshot();
		jdbc.update("""
				INSERT INTO code_chunk (snapshot_id, repository_id, file_path, class_name, method_name, chunk_type, start_line,
				    end_line, content, content_hash)
				VALUES (?, ?, 'src/main/java/x/OwnerRepository.java', 'x.OwnerRepository', 'findByLastName', 'METHOD', 3, 5,
				    'Collection<Owner> findByLastName(String last_name);', ?)
				""", ids[1], ids[0], "a".repeat(64));

		String tsv = jdbc.queryForObject("SELECT tsv::text FROM code_chunk", String.class);

		assertThat(tsv).contains("'findbylastname'", "'find'", "'last'", "'name'", "'ownerrepository'", "'owner'",
				"'repository'", "'collection'");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM code_chunk WHERE tsv @@ to_tsquery('simple', '(ownerrepository | (owner & repository)) & last')",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void anEmbeddingMustHaveTheDimensionItDeclares() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO chunk_embedding (content_hash, embedding_model, embedding_model_version, dimensions, embedding)
				VALUES (?, 'm', 'v', 3, '{1,2}')
				""", "b".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void chunksAreRemovedWithTheirSnapshot() {
		UUID[] ids = snapshot();
		jdbc.update("""
				INSERT INTO code_chunk (snapshot_id, repository_id, file_path, chunk_type, start_line, end_line, content, content_hash)
				VALUES (?, ?, 'README.md', 'DOC_SECTION', 1, 2, 'Hello', ?)
				""", ids[1], ids[0], "c".repeat(64));

		jdbc.update("DELETE FROM repository WHERE id = ?", ids[0]);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunk", Integer.class)).isZero();
	}

}
