package com.repointelligence.analysis.spi;

import java.util.UUID;

/**
 * The deterministic input of an impact analysis: a symbol picked from {@code code_symbol}. A free-text change
 * description never reaches the analyzer; it can only help a person pick the symbol.
 *
 * @param maxDepth hops of indirect dependents to follow (1 = direct dependents only)
 * @param maxComponents cap on the affected components that are returned
 */
public record ImpactQuery(UUID targetId, int maxDepth, int maxComponents) {

	public ImpactQuery {
		if (maxDepth < 1) {
			throw new IllegalArgumentException("maxDepth must be at least 1");
		}
		if (maxComponents < 1) {
			throw new IllegalArgumentException("maxComponents must be at least 1");
		}
	}

}
