package com.repointelligence.rag.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "A question about one snapshot. conversationId continues a conversation of the same "
		+ "repository and snapshot; follow-ups see only a bounded summary of earlier turns.")
public record QuestionRequestDTO(@NotBlank @Size(max = 2000) String question, UUID conversationId,
		@Schema(description = "Preferred provider id; used only if it is permitted and healthy.")
		@Size(max = 50) @Pattern(regexp = "[a-z0-9-]*") String provider) {
}
