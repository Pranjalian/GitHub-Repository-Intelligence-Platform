package com.repointelligence.rag.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Exact symbol lookup in the MVP 2 symbol graph ({@code code_symbol}) of one run of one snapshot. A name
 * matches a symbol's simple name ({@code OwnerController}, {@code findOwner}), its qualified name
 * ({@code org.x.OwnerController}, {@code org.x.OwnerController#findOwner}) or {@code Type.member}; case is
 * ignored, an exact-case match ranks first, then types before members. No fuzzy matching: an identifier
 * either exists or it does not.
 */
@Repository
public class SymbolLookup {

	private final JdbcTemplate jdbc;

	public SymbolLookup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A symbol row (no source text: the symbol graph stores positions only). */
	public record SymbolRow(UUID id, String symbolType, String qualifiedName, String signature, String filePath,
			Integer startLine, Integer endLine, String modulePath) {

		public String simpleName() {
			int cut = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('#'));
			return qualifiedName.substring(cut + 1);
		}

		/** The type a member belongs to, or the type itself. */
		public String typeName() {
			int hash = qualifiedName.indexOf('#');
			return hash < 0 ? qualifiedName : qualifiedName.substring(0, hash);
		}

		public boolean isType() {
			return !qualifiedName.contains("#");
		}

	}

	public boolean hasSymbols(UUID snapshotId, UUID runId) {
		Boolean exists = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM code_symbol WHERE snapshot_id = ? AND run_id = ?)", Boolean.class, snapshotId,
				runId);
		return Boolean.TRUE.equals(exists);
	}

	public List<SymbolRow> find(UUID snapshotId, UUID runId, String name, String module, int limit) {
		String member = name.contains("#") ? name : name.contains(".") ? name.substring(0, name.lastIndexOf('.')) + "#"
				+ name.substring(name.lastIndexOf('.') + 1) : null;
		List<Object> params = new ArrayList<>(List.of(name, snapshotId, runId, name, name));
		StringBuilder sql = new StringBuilder("""
				SELECT s.id, s.symbol_type, s.fully_qualified_name, s.signature, s.file_path, s.start_line, s.end_line,
				       s.module_path,
				       (regexp_replace(s.fully_qualified_name, '^.*[.#]', '') = ?) AS exact_case
				FROM code_symbol s
				WHERE s.snapshot_id = ? AND s.run_id = ?
				  AND (lower(regexp_replace(s.fully_qualified_name, '^.*[.#]', '')) = lower(?)
				       OR lower(s.fully_qualified_name) = lower(?)""");
		if (member != null) {
			// "Type.member" or "pkg.Type#member": compare the last type segment and the member
			sql.append(" OR lower(regexp_replace(s.fully_qualified_name, '^(?:.*[.])?([^.#]+#[^#]+)$', E'\\\\1')) = lower(?)");
			params.add(member.substring(Math.max(0, member.lastIndexOf('.', member.indexOf('#')) + 1)));
		}
		sql.append(')');
		if (module != null && !module.isBlank()) {
			sql.append(" AND (s.module_path = ? OR s.module_path LIKE ? ESCAPE '!')");
			params.add(module);
			params.add(module.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "/%");
		}
		sql.append("""

				ORDER BY exact_case DESC,
				         CASE WHEN position('#' IN s.fully_qualified_name) = 0 THEN 0 ELSE 1 END,
				         s.fully_qualified_name, s.file_path, s.start_line
				LIMIT ?""");
		params.add(limit);
		return jdbc.query(sql.toString(),
				(rs, n) -> new SymbolRow(rs.getObject("id", UUID.class), rs.getString("symbol_type"),
						rs.getString("fully_qualified_name"), rs.getString("signature"), rs.getString("file_path"),
						(Integer) rs.getObject("start_line"), (Integer) rs.getObject("end_line"), rs.getString("module_path")),
				params.toArray());
	}

}
