package com.repointelligence.ai.api;

import com.repointelligence.ai.application.InsightType;
import com.repointelligence.ai.provider.AIStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * An AI insight. {@code aiGenerated} is true exactly when there is an explanation; otherwise
 * {@code status} and {@code reason} say why there is none (AI disabled, no permitted provider, budget,
 * rate limit, timeout, invalid output). {@code evidence} is what was actually sent, after secret scrubbing.
 */
@Schema(description = "AI-generated explanation with the evidence it was based on, or the reason it is unavailable.")
public record InsightDTO(InsightType type, String targetId,
		@Schema(description = "What was explained, e.g. a class name or finding title.") String targetLabel,
		AIStatus status, boolean aiGenerated,
		@Schema(description = "Markdown; render without raw HTML.") String explanation,
		@Schema(description = "The validated structured answer (summary, keyPoints, risks, ...).") Object structured,
		String provider, String model, String promptVersion, boolean fromCache,
		@Schema(description = "True when a fallback provider answered.") boolean fallback, String reason,
		List<EvidenceDTO> evidence,
		@Schema(description = "Lines withheld from the prompt because a possible secret was detected.") int withheldLines,
		List<String> warnings, List<String> limitations, String commitSha, Instant generatedAt, String setupGuideUrl) {

	@Schema(description = "One data block sent to the model, as sent (scrubbed).")
	public record EvidenceDTO(String id, String kind, String filePath, Integer startLine, Integer endLine, String sourceUrl,
			boolean truncated, String text) {
	}

}
