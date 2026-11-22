package com.repointelligence.rag.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "One search hit: a symbol from the symbol graph or a chunk from the index, with a GitHub "
		+ "permalink at the snapshot's commit. snippet is secret-scrubbed repository text (untrusted).")
public record SearchResultDTO(@Schema(description = "SYMBOL or CHUNK") String kind, UUID chunkId, UUID symbolId,
		@Schema(description = "CLASS, METHOD, ... for symbols") String symbolType, String name, String qualifiedName,
		String signature, String filePath, Integer startLine, Integer endLine, String language, String module,
		@Schema(description = "CLASS, METHOD, CONFIG_SECTION, DOC_SECTION or LINE_WINDOW for chunks") String chunkType,
		String section, Double score, String snippet, String sourceUrl, boolean redacted) {
}
