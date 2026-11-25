package com.repointelligence.ai.api;

import com.repointelligence.ai.application.InsightType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "What to explain. targetId depends on the type: a symbol id (EXPLAIN_CLASS, EXPLAIN_METHOD, "
		+ "SUGGEST_REFACTORING), a finding id (EXPLAIN_FINDING), a module path (SUMMARIZE_MODULE) or nothing "
		+ "(TECH_DEBT_SUMMARY).")
public record InsightRequestDTO(@NotNull InsightType type, @Size(max = 1024) String targetId,
		@Schema(description = "Preferred provider id; used only if it is permitted and healthy.")
		@Size(max = 50) @Pattern(regexp = "[a-z0-9-]*") String provider,
		@Schema(description = "true skips the cache for this call and stores the new answer. The call still counts "
				+ "against the analysis call budget and the usage records. Omitted means false.") Boolean refresh) {

	public InsightRequestDTO(InsightType type, String targetId, String provider) {
		this(type, targetId, provider, null);
	}

	public boolean refreshRequested() {
		return Boolean.TRUE.equals(refresh);
	}

}
