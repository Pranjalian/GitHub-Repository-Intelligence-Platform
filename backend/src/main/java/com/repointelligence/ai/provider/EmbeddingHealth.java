package com.repointelligence.ai.provider;

/**
 * Health of a provider's embedding model, with the model version the provider reports. The version is
 * part of the embedding cache key, so re-pulling a model under the same name never mixes vectors.
 *
 * @param modelVersion e.g. the Ollama digest prefix or Gemini's model version; null unless UP
 */
public record EmbeddingHealth(ProviderHealth health, String model, String modelVersion) {

	public boolean isUp() {
		return health != null && health.isUp() && modelVersion != null;
	}

}
