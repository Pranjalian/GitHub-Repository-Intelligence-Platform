package com.repointelligence.ai.provider;

/**
 * What a provider/model can do, from configuration plus runtime checks (e.g. the model list of the
 * provider), never from hard-coded assumptions about models.
 *
 * @param maxContextTokens null when neither the provider nor the configuration states it
 * @param source {@code CONFIGURATION} or {@code RUNTIME}: where the values were read
 */
public record AIProviderCapabilities(boolean supportsChat, boolean supportsStructuredOutput,
		boolean supportsEmbeddings, boolean supportsStreaming, Integer maxContextTokens, String modelName,
		String source) {

	public static AIProviderCapabilities fromConfiguration(String model, Integer maxContextTokens,
			boolean structuredOutput) {
		return new AIProviderCapabilities(true, structuredOutput, false, false, maxContextTokens, model,
				"CONFIGURATION");
	}

}
