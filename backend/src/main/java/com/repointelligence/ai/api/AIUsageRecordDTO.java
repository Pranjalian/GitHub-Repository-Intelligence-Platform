package com.repointelligence.ai.api;

import com.repointelligence.ai.domain.CostSource;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.UsageSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One AI provider call or cache hit. inputTokens/outputTokens are null unless the provider "
		+ "reported them; estimated* are local estimates. estimatedCost is null unless pricing is configured.")
public record AIUsageRecordDTO(UUID id, Instant createdAt, String provider, String model, String feature,
		String promptVersion, UUID repositoryId, UUID snapshotId, UUID runId, AIStatus status, boolean cacheHit,
		Integer inputTokens, Integer outputTokens, Integer estimatedInputTokens, Integer estimatedOutputTokens,
		UsageSource usageSource, BigDecimal estimatedCost, CostSource costSource, String currency, Long latencyMs,
		String detail) {
}
