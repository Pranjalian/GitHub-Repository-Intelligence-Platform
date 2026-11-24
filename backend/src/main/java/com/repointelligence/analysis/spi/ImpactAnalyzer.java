package com.repointelligence.analysis.spi;

import java.util.List;
import java.util.Set;

/**
 * Estimates what a change to one symbol may affect, from the stored dependency graph (never from a
 * model, never by running code). One implementation per language pack; the core picks the one whose
 * {@link #supports} accepts the target. Like every analyzer it returns a result and persists nothing.
 */
public interface ImpactAnalyzer {

	Descriptor descriptor();

	/** Whether this analyzer knows the language of the target symbol. */
	boolean supports(ImpactGraph.Node target);

	ImpactResult analyze(ImpactQuery query, ImpactGraph graph);

	/** Identity and honesty about the analyzer: what it covers and what it cannot see. */
	record Descriptor(String id, String name, String version, Set<String> languages, List<String> limitations) {

		public Descriptor {
			languages = Set.copyOf(languages);
			limitations = List.copyOf(limitations);
		}

	}

}
