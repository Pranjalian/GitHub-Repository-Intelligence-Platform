package com.repointelligence.rag.vectorstore;

import com.repointelligence.rag.retrieval.ChunkFilter;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exact cosine search computed in the application, for databases without pgvector (the embedded PostgreSQL
 * of the tests, a native install without the extension). Loads the snapshot's vectors of one model version
 * and ranks them; refuses (with a reason) above {@code rag.retrieval.max-in-process-vectors} instead of
 * loading an unbounded amount. Same ordering rules as {@link PgVectorStore}: similarity, then path and line.
 */
public class InProcessVectorStore implements VectorStore {

	private final JdbcTemplate jdbc;

	private final int maxVectors;

	public InProcessVectorStore(JdbcTemplate jdbc, int maxVectors) {
		this.jdbc = jdbc;
		this.maxVectors = maxVectors;
	}

	@Override
	public String name() {
		return "in-process";
	}

	private record Row(UUID id, String path, int line, float[] vector) {
	}

	@Override
	public VectorSearch nearest(UUID snapshotId, UUID repositoryId, String model, String version, float[] query,
			ChunkFilter filter, int k) {
		if (!usable(query)) {
			return VectorSearch.unavailable("the query embedding is empty");
		}
		List<Object> params = new ArrayList<>(List.of(model, version, snapshotId, repositoryId, query.length));
		String conditions = filter.sql(params);
		params.add(maxVectors + 1);
		List<Row> rows = jdbc.query("""
				SELECT c.id, c.file_path, c.start_line, e.embedding
				FROM code_chunk c
				JOIN chunk_embedding e ON e.content_hash = c.content_hash
				 AND e.embedding_model = ? AND e.embedding_model_version = ?
				WHERE c.snapshot_id = ? AND c.repository_id = ? AND e.dimensions = ?""" + conditions + """

				LIMIT ?
				""", (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("file_path"), rs.getInt("start_line"),
				floats(rs.getArray("embedding"))), params.toArray());
		if (rows.size() > maxVectors) {
			return VectorSearch.unavailable("the snapshot has more than " + maxVectors
					+ " embedded chunks, too many to rank without pgvector (rag.retrieval.max-in-process-vectors)");
		}
		double queryNorm = norm(query);
		record Scored(Row row, double similarity) {
		}
		List<Scored> scored = new ArrayList<>();
		for (Row row : rows) {
			double norm = norm(row.vector());
			if (norm == 0) {
				continue;
			}
			scored.add(new Scored(row, dot(query, row.vector()) / (queryNorm * norm)));
		}
		scored.sort(Comparator.comparingDouble((Scored s) -> -s.similarity())
			.thenComparing(s -> s.row().path())
			.thenComparingInt(s -> s.row().line()));
		return new VectorSearch(scored.stream().limit(k).map(s -> new VectorHit(s.row().id(), s.similarity())).toList(),
				null);
	}

	static boolean usable(float[] vector) {
		return vector != null && vector.length > 0 && norm(vector) > 0;
	}

	static double cosine(float[] a, float[] b) {
		return dot(a, b) / (norm(a) * norm(b));
	}

	private static double dot(float[] a, float[] b) {
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += (double) a[i] * b[i];
		}
		return sum;
	}

	private static double norm(float[] v) {
		double sum = 0;
		for (float f : v) {
			sum += (double) f * f;
		}
		return Math.sqrt(sum);
	}

	private static float[] floats(Array array) throws java.sql.SQLException {
		Object raw = array.getArray();
		if (raw instanceof Float[] boxed) {
			float[] values = new float[boxed.length];
			for (int i = 0; i < boxed.length; i++) {
				values[i] = boxed[i] == null ? 0f : boxed[i];
			}
			return values;
		}
		if (raw instanceof float[] primitive) {
			return primitive;
		}
		throw new java.sql.SQLException("unexpected array type " + raw.getClass());
	}

}
