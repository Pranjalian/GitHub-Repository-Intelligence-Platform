package com.repointelligence.rag.embedding;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code chunk_embedding}: vectors keyed by content hash, model and model version. Rows are only ever
 * added (a new model or version gets new rows; old ones stay valid for indexes built with them) and are
 * removed only when no chunk references their hash any more.
 */
@Repository
public class EmbeddingStore {

	private final JdbcTemplate jdbc;

	public EmbeddingStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One vector to store. */
	public record Vector(String contentHash, float[] values) {
	}

	/** Stores vectors; an existing row for the same key is kept (the same content gives the same vector). */
	public void save(String model, String version, List<Vector> vectors) {
		jdbc.batchUpdate("""
				INSERT INTO chunk_embedding (content_hash, embedding_model, embedding_model_version, dimensions, embedding)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (content_hash, embedding_model, embedding_model_version) DO NOTHING
				""", vectors, 100, (ps, vector) -> {
			Float[] boxed = new Float[vector.values().length];
			for (int i = 0; i < boxed.length; i++) {
				boxed[i] = vector.values()[i];
			}
			ps.setString(1, vector.contentHash());
			ps.setString(2, model);
			ps.setString(3, version);
			ps.setInt(4, boxed.length);
			ps.setArray(5, ps.getConnection().createArrayOf("float4", boxed));
		});
	}

	/** The dimension already stored for a model version, or null when it has no vectors yet. */
	public Integer dimensions(String model, String version) {
		List<Integer> found = jdbc.queryForList(
				"SELECT dimensions FROM chunk_embedding WHERE embedding_model = ? AND embedding_model_version = ? LIMIT 1",
				Integer.class, model, version);
		return found.isEmpty() ? null : found.get(0);
	}

	/** Removes vectors whose content no stored chunk has any more (e.g. after a repository was deleted). */
	public int purgeUnreferenced() {
		return jdbc.update("""
				DELETE FROM chunk_embedding e
				WHERE NOT EXISTS (SELECT 1 FROM code_chunk c WHERE c.content_hash = e.content_hash)
				""");
	}

}
