package com.repointelligence.rag.api;

import com.repointelligence.rag.qa.QAService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Repository Q&A over the snapshot's index (MVP 4). No model is not an error: 200 with status UNAVAILABLE. */
@Tag(name = "Q&A", description = "Grounded answers about one snapshot, with source references (MVP 4).")
@RestController
public class QAController {

	private final QAService service;

	public QAController(QAService service) {
		this.service = service;
	}

	@Operation(summary = "Ask a question about a snapshot",
			description = "Retrieves the most relevant chunks (keyword, symbol and, with embeddings, semantic search), "
					+ "sends them with the question through the AI gateway (secret scrub, token budget, cache, call budget, "
					+ "schema validation) and returns an answer whose citations are checked against the chunks that were "
					+ "sent. INSUFFICIENT_EVIDENCE says what was searched; UNAVAILABLE (no model) returns deterministic "
					+ "search results instead; NOT_INDEXED asks to index the snapshot first. 404 for a snapshot of another "
					+ "repository or a conversation of another snapshot.")
	@PostMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/questions")
	public QuestionAnswerDTO ask(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@Valid @RequestBody QuestionRequestDTO request) {
		return service.ask(repositoryId, snapshotId, request);
	}

}
