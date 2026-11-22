package com.repointelligence.ai.provider;

import java.util.Map;
import java.util.UUID;

/**
 * One provider call. Built only by the AI gateway, after secret scrubbing and token budgeting:
 * {@code userPrompt} is the template's task text followed by delimited, untrusted data blocks.
 *
 * @param model model override from a feature route; null means the provider's configured chat model
 * @param responseSchema JSON schema the answer must satisfy; null for free text
 * @param feature feature id such as {@code explain-class}, for routing and usage records
 */
public record AIRequest(String systemPrompt, String userPrompt, String promptVersion, int maxOutputTokens,
		double temperature, Map<String, Object> responseSchema, String model, UUID repositoryId, UUID snapshotId,
		String feature) {

	public AIRequest withModel(String override) {
		return new AIRequest(systemPrompt, userPrompt, promptVersion, maxOutputTokens, temperature, responseSchema,
				override, repositoryId, snapshotId, feature);
	}

}
