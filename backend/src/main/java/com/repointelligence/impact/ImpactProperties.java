package com.repointelligence.impact;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code impact.*}: change-impact analysis (MVP 5). The analysis itself needs no model; the optional AI
 * summary and the description-to-symbol suggestions are AI features configured under {@code ai.*}.
 *
 * @param defaultDepth hops of indirect dependents followed when the request names none
 * @param maxDepth the largest depth a request may ask for
 * @param maxComponents affected components returned (the rest are counted and reported as truncated)
 * @param maxGraphRows rows a graph traversal may return before it is cut off and reported
 * @param maxPickerResults symbols returned by the symbol picker
 * @param suggestionCandidates candidate symbols sent to the model when it maps a change description
 * @param diagramNodes components drawn in the Mermaid chain (the rest are counted in a note)
 */
@ConfigurationProperties("impact")
public record ImpactProperties(@DefaultValue("3") int defaultDepth, @DefaultValue("6") int maxDepth,
		@DefaultValue("300") int maxComponents, @DefaultValue("20000") int maxGraphRows,
		@DefaultValue("25") int maxPickerResults, @DefaultValue("40") int suggestionCandidates,
		@DefaultValue("30") int diagramNodes) {

	public ImpactProperties {
		if (defaultDepth < 1 || maxDepth < defaultDepth) {
			throw new IllegalArgumentException("impact.max-depth must be >= impact.default-depth >= 1");
		}
	}

}
