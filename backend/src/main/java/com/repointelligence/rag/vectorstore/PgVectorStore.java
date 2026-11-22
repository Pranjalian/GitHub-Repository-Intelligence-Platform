package com.repointelligence.rag.vectorstore;

import com.repointelligence.rag.retrieval.ChunkFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exact cosine search in PostgreSQL with pgvector ({@code <=>}): stored {@code real[]} vectors are cast to
 * {@code vector} in the query, so the schema does not depend on the extension. The scan is limited to the
 * snapshot's chunks of one model version; no approximate index is used (results are exact and
 * deterministic, and a snapshot has at most {@code rag.indexing.max-chunks} chunks).
 */
public class PgVectorStore implements VectorStore {

	private final JdbcTemplate jdbc;

	public PgVectorStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public String name() {
		return "pgvector";
	}

	@Override
	public VectorSearch nearest(UUID snapshotId, UUID repositoryId, String model, String version, float[] query,
			ChunkFilter filter, int k) {
		if (!InProcessVectorStore.usable(query)) {
			return VectorSearch.unavailable("the query embedding is empty");
		}
		String literal = literal(query);
		List<Object> params = new ArrayList<>(List.of(literal, model, version, snapshotId, repositoryId, query.length));
		String conditions = filter.sql(params);
		params.add(literal);
		params.add(k);
		List<VectorHit> hits = jdbc.query("""
				SELECT c.id, 1 - (e.embedding::vector <=> ?::vector) AS similarity
				FROM code_chunk c
				JOIN chunk_embedding e ON e.content_hash = c.content_hash
				 AND e.embedding_model = ? AND e.embedding_model_version = ?
				WHERE c.snapshot_id = ? AND c.repository_id = ? AND e.dimensions = ?""" + conditions + """

				ORDER BY e.embedding::vector <=> ?::vector, c.file_path, c.start_line
				LIMIT ?
				""", (rs, n) -> new VectorHit(rs.getObject("id", UUID.class), rs.getDouble("similarity")), params.toArray());
		// a zero stored vector gives NaN; it carries no meaning and is dropped
		return new VectorSearch(hits.stream().filter(h -> !Double.isNaN(h.similarity())).toList(), null);
	}

	/** pgvector's text form, e.g. {@code [0.1,-2.0E-4]}. */
	static String literal(float[] vector) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append(vector[i]);
		}
		return out.append(']').toString();
	}

}
