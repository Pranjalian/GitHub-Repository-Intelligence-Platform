package com.repointelligence.ai.provider;

import java.time.Instant;
import java.util.List;

/**
 * A provider's embeddings: one vector per input, in input order, all of the same dimension. Only
 * {@link AIStatus#OK} carries vectors; every other status has a {@code detail} in words (never a key or
 * the input text).
 *
 * @param inputTokens tokens the provider reported for the batch, else null
 * @param retryAfter for {@link AIStatus#RATE_LIMITED}: when the provider may be used again, if it said
 */
public record EmbeddingResponse(AIStatus status, List<float[]> vectors, String model, Integer inputTokens,
		String detail, Instant retryAfter) {

	public static EmbeddingResponse ok(String model, List<float[]> vectors, Integer inputTokens) {
		return new EmbeddingResponse(AIStatus.OK, List.copyOf(vectors), model, inputTokens, null, null);
	}

	public static EmbeddingResponse failure(AIStatus status, String model, String detail) {
		return new EmbeddingResponse(status, List.of(), model, null, detail, null);
	}

	public static EmbeddingResponse rateLimited(String model, String detail, Instant retryAfter) {
		return new EmbeddingResponse(AIStatus.RATE_LIMITED, List.of(), model, null, detail, retryAfter);
	}

	public boolean isOk() {
		return status == AIStatus.OK;
	}

}
