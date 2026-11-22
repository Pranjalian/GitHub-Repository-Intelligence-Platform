package com.repointelligence.rag.indexing;

/**
 * Whether the chunks of an index have embeddings. Embeddings are optional: without them, keyword and symbol
 * search and keyword-only retrieval still work; the status says why semantic search is missing.
 */
public enum EmbeddingStatus {

	/** Waiting for the job. */
	PENDING,
	/** No embedding provider or model is configured (or AI is disabled, or a hosted provider is not permitted). */
	NOT_CONFIGURED,
	/** Configured, but the provider or model could not be reached or used. */
	UNAVAILABLE,
	/** Every chunk has an embedding. */
	COMPLETED,
	/** Some chunks have embeddings (a batch failed or the per-index request budget was reached). */
	PARTIAL,
	/** Embedding was attempted and failed for every chunk. */
	FAILED

}
