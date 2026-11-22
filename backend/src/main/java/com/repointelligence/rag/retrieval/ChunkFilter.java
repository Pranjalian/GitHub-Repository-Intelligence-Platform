package com.repointelligence.rag.retrieval;

import java.util.List;
import java.util.Locale;

/**
 * Optional metadata filters for search and retrieval. Blank values mean "any". {@code module} matches the
 * module path and everything below it.
 */
public record ChunkFilter(String language, String module, String fileType) {

	public static final ChunkFilter NONE = new ChunkFilter(null, null, null);

	public ChunkFilter {
		language = blankToNull(language);
		module = blankToNull(module);
		fileType = blankToNull(fileType) == null ? null : fileType.trim().toUpperCase(Locale.ROOT);
	}

	/** SQL conditions on alias {@code c} (code_chunk), appended with AND, and their parameters in order. */
	public String sql(List<Object> params) {
		StringBuilder sql = new StringBuilder();
		if (language != null) {
			sql.append(" AND lower(c.language) = lower(?)");
			params.add(language);
		}
		if (module != null) {
			sql.append(" AND (c.module_path = ? OR c.module_path LIKE ? ESCAPE '\\')");
			params.add(module);
			params.add(escapeLike(module.endsWith("/") ? module : module + "/") + "%");
		}
		if (fileType != null) {
			sql.append(" AND c.file_type = ?");
			params.add(fileType);
		}
		return sql.toString();
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
