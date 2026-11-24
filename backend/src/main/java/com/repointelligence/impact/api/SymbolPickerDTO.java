package com.repointelligence.impact.api;

import java.util.List;
import java.util.UUID;

/** Symbols that can be picked as an impact target (types, methods, constructors), found by name. */
public record SymbolPickerDTO(String status, String reason, String query, List<PickerSymbol> symbols) {

	public record PickerSymbol(UUID id, String kind, String name, String qualifiedName, String filePath, Integer line,
			String layer) {
	}

}
