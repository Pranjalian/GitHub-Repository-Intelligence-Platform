package com.repointelligence.impact.api;

import java.util.List;
import java.util.UUID;

/**
 * Candidate targets for a free-text change description. Suggestions are only proposals from a model, chosen from
 * symbols that exist in the snapshot; the person confirms one, and only then is it analysed.
 */
public record TargetSuggestionDTO(String status, boolean aiGenerated, String detail, String provider, String model,
		String promptVersion, List<Suggestion> suggestions, List<String> warnings) {

	public record Suggestion(UUID symbolId, String kind, String name, String qualifiedName, String filePath, Integer line,
			String reason) {
	}

}
