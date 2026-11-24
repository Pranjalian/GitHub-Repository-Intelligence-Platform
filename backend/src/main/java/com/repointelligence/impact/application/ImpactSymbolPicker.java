package com.repointelligence.impact.application;

import com.repointelligence.impact.ImpactProperties;
import com.repointelligence.impact.api.SymbolPickerDTO.PickerSymbol;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The deterministic symbol picker behind impact analysis: classes, interfaces, methods and constructors of one
 * run, found by name (a simple name, a qualified name or {@code Type.method}). Needs no model and no index.
 * LIKE wildcards in the query are escaped, and every query is bound to the snapshot and run.
 */
@Repository
public class ImpactSymbolPicker {

	private final NamedParameterJdbcTemplate jdbc;

	private final ImpactProperties properties;

	public ImpactSymbolPicker(NamedParameterJdbcTemplate jdbc, ImpactProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	public long symbolCount(UUID snapshotId, UUID runId) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM code_symbol WHERE snapshot_id = :snapshot AND run_id = :run",
				new MapSqlParameterSource("snapshot", snapshotId).addValue("run", runId), Long.class);
		return count == null ? 0 : count;
	}

	public List<PickerSymbol> find(UUID snapshotId, UUID runId, String query, int limit) {
		String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
		String escaped = escape(q);
		int lastDot = q.lastIndexOf('.');
		String memberForm = lastDot > 0 ? escape(q.substring(0, lastDot)) + "#" + escape(q.substring(lastDot + 1)) : escaped;
		MapSqlParameterSource params = new MapSqlParameterSource("snapshot", snapshotId).addValue("run", runId)
			.addValue("exact", q)
			.addValue("prefix", escaped + "%")
			.addValue("contains", "%" + escaped + "%")
			.addValue("member", "%" + memberForm + "%")
			.addValue("limit", Math.min(limit, properties.maxPickerResults()));
		String filter = q.isEmpty() ? "TRUE"
				: "(lower(s.fully_qualified_name) LIKE :contains ESCAPE '!' OR lower(s.fully_qualified_name) LIKE :member ESCAPE '!')";
		return jdbc.query("""
				SELECT s.id, s.symbol_type, s.fully_qualified_name, s.signature, s.file_path, s.start_line, l.layer,
				       CASE
				           WHEN lower(regexp_replace(s.fully_qualified_name, '^.*[.#]', '')) = :exact THEN 0
				           WHEN lower(regexp_replace(s.fully_qualified_name, '^.*[.#]', '')) LIKE :prefix ESCAPE '!' THEN 1
				           WHEN lower(regexp_replace(s.fully_qualified_name, '^.*[.#]', '')) LIKE :contains ESCAPE '!' THEN 2
				           ELSE 3
				       END AS rank
				FROM code_symbol s
				LEFT JOIN layer_assignment l ON l.snapshot_id = s.snapshot_id AND l.run_id = s.run_id
				     AND l.symbol_id = COALESCE(s.parent_symbol_id, s.id)
				WHERE s.snapshot_id = :snapshot AND s.run_id = :run
				  AND s.symbol_type IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'METHOD', 'CONSTRUCTOR') AND %s
				ORDER BY rank, CASE WHEN s.symbol_type IN ('METHOD', 'CONSTRUCTOR') THEN 1 ELSE 0 END,
				         s.fully_qualified_name, s.signature
				LIMIT :limit
				""".formatted(filter), params, (rs, i) -> {
			String fqn = rs.getString("fully_qualified_name");
			String signature = rs.getString("signature");
			boolean member = fqn.contains("#");
			String owner = member ? fqn.substring(0, fqn.indexOf('#')) : fqn;
			String name = member ? simple(owner) + "." + (signature != null ? signature : fqn.substring(fqn.indexOf('#') + 1))
					: simple(fqn);
			String qualified = member ? owner + "#" + (signature != null ? signature : fqn.substring(fqn.indexOf('#') + 1)) : fqn;
			return new PickerSymbol(rs.getObject("id", UUID.class), rs.getString("symbol_type"), name, qualified,
					rs.getString("file_path"), (Integer) rs.getObject("start_line"), rs.getString("layer"));
		});
	}

	private static String simple(String type) {
		return type.substring(type.lastIndexOf('.') + 1);
	}

	/** Escapes LIKE wildcards with '!' (the ESCAPE character of the queries above). */
	private static String escape(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

}
