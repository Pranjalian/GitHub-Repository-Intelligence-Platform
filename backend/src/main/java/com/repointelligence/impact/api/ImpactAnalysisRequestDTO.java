package com.repointelligence.impact.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param targetSymbolId a class, interface, method or constructor picked from the snapshot's symbols
 * @param changeDescription optional free text describing the change; it is only used as context of the optional
 * AI summary and never selects or changes the target
 * @param depth hops of indirect dependents (default and maximum come from {@code impact.*})
 * @param includeAiSummary also ask the configured model for a summary of the result (never changes it)
 * @param provider preferred AI provider for the summary
 */
public record ImpactAnalysisRequestDTO(@NotNull UUID targetSymbolId, @Size(max = 1000) String changeDescription,
		Integer depth, Boolean includeAiSummary, String provider) {

	public boolean wantsAiSummary() {
		return Boolean.TRUE.equals(includeAiSummary);
	}

}
