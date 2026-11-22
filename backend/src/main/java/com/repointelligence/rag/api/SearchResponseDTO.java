package com.repointelligence.rag.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Deterministic search in one snapshot (no model involved). status OK, NOT_INDEXED (text "
		+ "search needs an index) or NOT_SUPPORTED (no symbol graph and no index), always with a reason when not OK.")
public record SearchResponseDTO(String query, String type, String status, String reason, String commitSha,
		@Schema(description = "SYMBOL_GRAPH, CHUNK_NAMES or KEYWORD_INDEX") String source, List<SearchResultDTO> results,
		List<String> terms, IndexStatusDTO index) {
}
