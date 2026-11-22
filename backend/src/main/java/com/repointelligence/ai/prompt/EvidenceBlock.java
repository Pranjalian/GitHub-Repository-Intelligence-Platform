package com.repointelligence.ai.prompt;

/**
 * One piece of untrusted repository data handed to a model, with where it came from. After scrubbing
 * and budgeting, the blocks that were actually sent are returned with the answer as its evidence.
 *
 * @param id reference the model cites, e.g. {@code E1}
 * @param kind {@code SOURCE}, {@code DECLARATION}, {@code FINDING}, {@code FINDINGS}, {@code TARGET}, ...
 * @param filePath null for blocks that are not file excerpts
 * @param startLine first line of the excerpt as sent (after trimming)
 * @param endLine last line of the excerpt as sent
 * @param sourceUrl GitHub permalink at the snapshot commit, when there is a file
 * @param truncated true when the token budget cut the excerpt short
 */
public record EvidenceBlock(String id, String kind, String filePath, Integer startLine, Integer endLine,
		String text, String sourceUrl, boolean truncated) {

	public EvidenceBlock withText(String newText, Integer newEnd, boolean wasTruncated) {
		return new EvidenceBlock(id, kind, filePath, startLine, newEnd, newText, sourceUrl, truncated || wasTruncated);
	}

	public EvidenceBlock withId(String newId) {
		return new EvidenceBlock(newId, kind, filePath, startLine, endLine, text, sourceUrl, truncated);
	}

}
