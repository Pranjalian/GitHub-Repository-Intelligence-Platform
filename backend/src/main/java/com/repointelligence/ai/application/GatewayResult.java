package com.repointelligence.ai.application;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.provider.AIStatus;
import java.time.Instant;
import java.util.List;

/**
 * The gateway's answer. Content is only present for {@link AIStatus#OK}; {@code aiGenerated} is true
 * exactly then. {@code evidence} lists the blocks that were actually sent (after scrubbing and budgeting).
 *
 * @param fallback true when a fallback provider answered instead of the requested one
 * @param warnings e.g. evidence references in the answer that name no evidence block (they are dropped)
 */
public record GatewayResult(AIStatus status, String content, Object structured, String provider, String model,
		String promptVersion, boolean fromCache, boolean fallback, String detail, List<EvidenceBlock> evidence,
		int withheldLines, List<String> warnings, Instant generatedAt) {

	public boolean aiGenerated() {
		return status == AIStatus.OK;
	}

	static GatewayResult notRun(AIStatus status, String promptVersion, String detail) {
		return new GatewayResult(status, null, null, null, null, promptVersion, false, false, detail, List.of(), 0,
				List.of(), null);
	}

}
