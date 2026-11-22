package com.repointelligence.architecture.api;

import com.repointelligence.architecture.domain.SymbolType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** A code symbol of a snapshot with its direct members (for types), as read by the parser. */
@Schema(description = "A type or member read from the syntax tree, with its members and a GitHub permalink.")
public record SymbolDTO(UUID id, SymbolType type, String name, String fullyQualifiedName, String signature,
		String filePath, Integer startLine, Integer endLine, String sourceUrl, List<String> annotations,
		List<SymbolDTO> members) {
}
