package com.repointelligence.rag.retrieval;

import com.repointelligence.rag.indexing.StoredChunk;

/**
 * A chunk chosen by hybrid retrieval, with its fused score and where it ranked in each source (null when a
 * source did not return it).
 *
 * @param similarity cosine similarity from the semantic source, when it ranked there
 */
public record RetrievedChunk(StoredChunk chunk, double score, Integer keywordRank, Integer symbolRank,
		Integer semanticRank, Double similarity) {
}
