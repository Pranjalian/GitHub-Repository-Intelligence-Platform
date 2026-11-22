package com.repointelligence.ai.prompt;

import java.util.Map;

/**
 * A versioned prompt template from {@code ai/prompt/<id>.v<version>.yml}. {@code system} and
 * {@code task} are fixed text written by us; repository-derived content is never substituted into them.
 * It is appended as delimited data blocks by {@link PromptAssembler}.
 *
 * @param schema JSON schema of the answer; null for free text
 */
public record PromptTemplate(String id, int version, String feature, String system, String task,
		Map<String, Object> schema, int maxOutputTokens, double temperature) {

	/** e.g. {@code explain-class.v1}; part of the cache key and of every usage record. */
	public String promptVersion() {
		return id + ".v" + version;
	}

}
