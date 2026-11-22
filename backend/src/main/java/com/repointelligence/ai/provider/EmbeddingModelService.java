package com.repointelligence.ai.provider;

import java.util.List;

/**
 * Provider-neutral embedding interface, separate from chat: the embedding model is its own setting
 * ({@code ai.providers.<id>.embedding-model}), because a chat model is not assumed to be suitable for
 * embeddings. Like {@link AIModelService}, implementations apply the configured timeout and retry limit,
 * never retry a rate limit, and return a status instead of throwing.
 */
public interface EmbeddingModelService {

	String getProviderId();

	DataHandling dataHandling();

	/** The configured embedding model, or null when none is set. */
	String embeddingModel();

	/** Reachability, model availability and the model version; never throws. */
	EmbeddingHealth embeddingHealth();

	/** One vector per input, in order; never throws for provider failures. */
	EmbeddingResponse embed(List<String> inputs, EmbeddingPurpose purpose);

}
