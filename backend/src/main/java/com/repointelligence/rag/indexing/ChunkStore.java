package com.repointelligence.rag.indexing;

import com.repointelligence.rag.chunking.ChunkDraft;
import com.repointelligence.rag.chunking.ChunkType;
import com.repointelligence.rag.retrieval.ChunkFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC access to {@code code_chunk} (generated tsvector column, bulk inserts). Every method takes the
 * snapshot and the repository and filters by both, so no query can return another commit's or another
 * repository's chunks. This is the leakage guard of the retrieval layer, enforced here rather than in callers.
 */
@Repository
public class ChunkStore {

	static final String COLUMNS = "c.id, c.file_path, c.language, c.file_type, c.module_path, c.class_name, "
			+ "c.method_name, c.chunk_type, c.section, c.start_line, c.end_line, c.content, c.content_hash, c.redacted";

	private static final RowMapper<StoredChunk> MAPPER = ChunkStore::map;

	private final JdbcTemplate jdbc;

	public ChunkStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A chunk to store and whether secret scrubbing changed any of its lines. */
	public record NewChunk(ChunkDraft draft, String contentHash, boolean redacted) {
	}

	/** Replaces all chunks of the snapshot in one transaction (re-indexing rebuilds them; embeddings stay). */
	@Transactional
	public void replace(UUID snapshotId, UUID repositoryId, List<NewChunk> chunks) {
		jdbc.update("DELETE FROM code_chunk WHERE snapshot_id = ? AND repository_id = ?", snapshotId, repositoryId);
		jdbc.batchUpdate("""
				INSERT INTO code_chunk (snapshot_id, repository_id, file_path, language, file_type, module_path, class_name,
				    method_name, chunk_type, section, start_line, end_line, content, content_hash, redacted)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", chunks, 200, (ps, chunk) -> {
			ChunkDraft d = chunk.draft();
			ps.setObject(1, snapshotId);
			ps.setObject(2, repositoryId);
			ps.setString(3, d.filePath());
			ps.setString(4, d.language());
			ps.setString(5, d.fileType());
			ps.setString(6, d.modulePath());
			ps.setString(7, truncate(d.className(), 1024));
			ps.setString(8, truncate(d.methodName(), 512));
			ps.setString(9, d.chunkType().name());
			ps.setString(10, truncate(d.section(), 512));
			ps.setInt(11, d.startLine());
			ps.setInt(12, d.endLine());
			ps.setString(13, d.content());
			ps.setString(14, chunk.contentHash());
			ps.setBoolean(15, chunk.redacted());
		});
	}

	public int count(UUID snapshotId, UUID repositoryId) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM code_chunk WHERE snapshot_id = ? AND repository_id = ?",
				Integer.class, snapshotId, repositoryId);
		return count == null ? 0 : count;
	}

	/**
	 * Content hashes of the snapshot that have no embedding for the model and version yet, each with one chunk
	 * to rebuild the embedded text from, ordered by hash (deterministic batches).
	 */
	public List<StoredChunk> missingEmbeddings(UUID snapshotId, UUID repositoryId, String model, String version) {
		return jdbc.query("SELECT DISTINCT ON (c.content_hash) " + COLUMNS + """
				 FROM code_chunk c
				WHERE c.snapshot_id = ? AND c.repository_id = ?
				  AND NOT EXISTS (SELECT 1 FROM chunk_embedding e WHERE e.content_hash = c.content_hash
				                  AND e.embedding_model = ? AND e.embedding_model_version = ?)
				ORDER BY c.content_hash, c.file_path, c.start_line
				""", MAPPER, snapshotId, repositoryId, model, version);
	}

	/** Chunks of the snapshot that have an embedding of the model and version. */
	public int embeddedCount(UUID snapshotId, UUID repositoryId, String model, String version) {
		Integer count = jdbc.queryForObject("""
				SELECT count(*) FROM code_chunk c
				WHERE c.snapshot_id = ? AND c.repository_id = ?
				  AND EXISTS (SELECT 1 FROM chunk_embedding e WHERE e.content_hash = c.content_hash
				              AND e.embedding_model = ? AND e.embedding_model_version = ?)
				""", Integer.class, snapshotId, repositoryId, model, version);
		return count == null ? 0 : count;
	}

	/** Chunks by id, restricted to the snapshot; ids of other snapshots are silently not returned. */
	public Map<UUID, StoredChunk> byIds(UUID snapshotId, UUID repositoryId, Collection<UUID> ids) {
		Map<UUID, StoredChunk> result = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return result;
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		List<Object> params = new ArrayList<>(List.of(snapshotId, repositoryId));
		params.addAll(ids);
		for (StoredChunk chunk : jdbc.query("SELECT " + COLUMNS + " FROM code_chunk c WHERE c.snapshot_id = ? AND "
				+ "c.repository_id = ? AND c.id IN (" + placeholders + ")", MAPPER, params.toArray())) {
			result.put(chunk.id(), chunk);
		}
		return result;
	}

	/** A ranked hit: chunk id and score. */
	public record Hit(UUID id, double score) {
	}

	/**
	 * Full-text matches for a tsquery (built by {@code QueryTerms} from identifier-safe tokens only), ranked with
	 * {@code ts_rank_cd}; ties broken by path and line so the order is stable.
	 */
	public List<Hit> keyword(UUID snapshotId, UUID repositoryId, String tsquery, ChunkFilter filter, int limit) {
		List<Object> params = new ArrayList<>();
		params.add(tsquery);
		params.add(snapshotId);
		params.add(repositoryId);
		String conditions = filter.sql(params);
		params.add(limit);
		return jdbc.query("SELECT c.id, ts_rank_cd(c.tsv, q) AS rank FROM code_chunk c, to_tsquery('simple', ?) q "
				+ "WHERE c.snapshot_id = ? AND c.repository_id = ? AND c.tsv @@ q" + conditions
				+ " ORDER BY rank DESC, c.file_path, c.start_line LIMIT ?",
				(rs, n) -> new Hit(rs.getObject("id", UUID.class), rs.getDouble("rank")), params.toArray());
	}

	/** Chunks of a file that overlap a line range (to map a symbol to its chunks), method chunks first. */
	public List<StoredChunk> overlapping(UUID snapshotId, UUID repositoryId, String filePath, int start, int end) {
		return jdbc.query("SELECT " + COLUMNS + " FROM code_chunk c WHERE c.snapshot_id = ? AND c.repository_id = ? "
				+ "AND c.file_path = ? AND c.start_line <= ? AND c.end_line >= ? "
				+ "ORDER BY CASE c.chunk_type WHEN 'METHOD' THEN 0 ELSE 1 END, (c.end_line - c.start_line), c.start_line",
				MAPPER, snapshotId, repositoryId, filePath, end, start);
	}

	/** Chunks whose type or method name equals one of the names (case-insensitive), for symbol search without a symbol graph. */
	public List<StoredChunk> byName(UUID snapshotId, UUID repositoryId, String name, ChunkFilter filter, int limit) {
		List<Object> params = new ArrayList<>(List.of(snapshotId, repositoryId, name, name, name, name));
		String conditions = filter.sql(params);
		params.add(limit);
		return jdbc.query("SELECT " + COLUMNS + " FROM code_chunk c WHERE c.snapshot_id = ? AND c.repository_id = ? "
				+ "AND (lower(c.class_name) = lower(?) OR right(lower(c.class_name), length(?) + 1) = '.' || lower(?) "
				+ "OR lower(c.method_name) = lower(?))"
				+ conditions + " ORDER BY c.file_path, c.start_line LIMIT ?", MAPPER, params.toArray());
	}

	private static StoredChunk map(ResultSet rs, int row) throws SQLException {
		return new StoredChunk(rs.getObject("id", UUID.class), rs.getString("file_path"), rs.getString("language"),
				rs.getString("file_type"), rs.getString("module_path"), rs.getString("class_name"),
				rs.getString("method_name"), ChunkType.valueOf(rs.getString("chunk_type")), rs.getString("section"),
				rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("content"), rs.getString("content_hash"),
				rs.getBoolean("redacted"));
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
