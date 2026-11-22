package com.repointelligence.rag.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "The answer to a question, or why there is none. status: OK (grounded answer with sources), "
		+ "INSUFFICIENT_EVIDENCE (says what was searched and what is missing), NOT_INDEXED, UNAVAILABLE (no model: "
		+ "deterministic search results are returned instead), SKIPPED (call budget), RATE_LIMITED, TIMEOUT, "
		+ "INVALID_OUTPUT or ERROR. aiGenerated is true only when a model wrote the answer.")
public record QuestionAnswerDTO(UUID conversationId, UUID messageId, String question, String status,
		boolean aiGenerated, String answer, boolean insufficientEvidence, String clarifyingQuestion,
		List<String> missingEvidence,
		@Schema(description = "Retrieved chunks the answer cites (validated: only chunks that were sent)") List<SourceDTO> sources,
		@Schema(description = "Every chunk handed to the model (or that would have been), best first") List<SourceDTO> retrieved,
		@Schema(description = "What retrieval searched: terms, symbols, semantic search") String searched,
		@Schema(description = "Deterministic search results when no model is available") List<SearchResultDTO> searchResults,
		String provider, String model, String promptVersion, boolean fromCache, boolean fallback, String reason,
		List<String> warnings, List<String> limitations, String commitSha, Instant generatedAt, IndexStatusDTO index) {

	@Schema(description = "A chunk of the snapshot with a GitHub permalink at the commit. snippet is secret-scrubbed "
			+ "repository text (untrusted).")
	public record SourceDTO(String ref, UUID chunkId, String filePath, Integer startLine, Integer endLine,
			String chunkType, String label, String snippet, String sourceUrl, Double score) {
	}

}
