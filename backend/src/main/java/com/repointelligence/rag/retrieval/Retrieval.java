package com.repointelligence.rag.retrieval;

import java.util.List;

/**
 * The result of hybrid retrieval and what was searched, so an answer can say exactly what was looked at when
 * the evidence is not enough.
 *
 * @param terms keyword terms used (stopwords removed)
 * @param symbols symbol names that matched exactly
 * @param semantic whether semantic search ran
 * @param semanticReason why semantic search did not run, when it did not
 */
public record Retrieval(List<RetrievedChunk> chunks, List<String> terms, List<String> symbols, int keywordHits,
		int symbolHits, int semanticHits, boolean semantic, String semanticReason) {

	public boolean isEmpty() {
		return chunks.isEmpty();
	}

	/** One line for an answer or a log: what the search covered. */
	public String searched() {
		StringBuilder out = new StringBuilder("keyword search for ")
			.append(terms.isEmpty() ? "(no meaningful words)" : String.join(", ", terms))
			.append(" (").append(keywordHits).append(" hit(s)); symbol lookup ")
			.append(symbols.isEmpty() ? "(no matching symbols)" : "matched " + String.join(", ", symbols))
			.append("; semantic search ");
		out.append(semantic ? semanticHits + " hit(s)" : "not used (" + semanticReason + ")");
		return out.toString();
	}

}
