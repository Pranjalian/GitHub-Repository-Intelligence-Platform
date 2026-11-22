package com.repointelligence.ai.provider;

import java.time.Instant;

/**
 * A provider's answer. Token counts are null unless the provider reported them; {@code usageSource}
 * says which. {@code detail} explains a non-OK status in words and never contains a key or the prompt.
 *
 * @param structured parsed and validated JSON (set by the gateway, not by providers)
 * @param retryAfter for {@link AIStatus#RATE_LIMITED}: when the provider may be used again, if it said
 */
public record AIResponse(String content, Object structured, Integer inputTokens, Integer outputTokens,
		UsageSource usageSource, String model, String provider, boolean fromCache, AIStatus status, String detail,
		Instant retryAfter) {

	public static AIResponse ok(String provider, String model, String content, Integer inputTokens,
			Integer outputTokens) {
		UsageSource source = inputTokens != null || outputTokens != null ? UsageSource.REPORTED : UsageSource.UNKNOWN;
		return new AIResponse(content, null, inputTokens, outputTokens, source, model, provider, false, AIStatus.OK,
				null, null);
	}

	public static AIResponse failure(String provider, String model, AIStatus status, String detail) {
		return new AIResponse(null, null, null, null, UsageSource.UNKNOWN, model, provider, false, status, detail, null);
	}

	public static AIResponse rateLimited(String provider, String model, String detail, Instant retryAfter) {
		return new AIResponse(null, null, null, null, UsageSource.UNKNOWN, model, provider, false,
				AIStatus.RATE_LIMITED, detail, retryAfter);
	}

	public static AIResponse unavailable(String detail) {
		return new AIResponse(null, null, null, null, UsageSource.UNKNOWN, null, null, false, AIStatus.UNAVAILABLE,
				detail, null);
	}

	public boolean isOk() {
		return status == AIStatus.OK;
	}

	public AIResponse withStructured(Object value) {
		return new AIResponse(content, value, inputTokens, outputTokens, usageSource, model, provider, fromCache, status,
				detail, retryAfter);
	}

	public AIResponse withStatus(AIStatus newStatus, String newDetail) {
		return new AIResponse(content, structured, inputTokens, outputTokens, usageSource, model, provider, fromCache,
				newStatus, newDetail, retryAfter);
	}

}
