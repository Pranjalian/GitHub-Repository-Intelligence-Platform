package com.repointelligence.ai.api;

import com.repointelligence.ai.provider.AIProviderCapabilities;
import com.repointelligence.ai.provider.DataHandling;
import com.repointelligence.ai.provider.ProviderHealth;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.util.List;

/** AI configuration as the settings page shows it. Never contains an API key. */
@Schema(description = "Configured AI providers, their health, data handling, capabilities and the limits.")
public record AIProvidersDTO(
		@Schema(description = "ai.enabled") boolean enabled,
		@Schema(description = "True when a permitted, healthy provider is available for a public repository.") boolean available,
		@Schema(description = "Why AI insights are unavailable; null when available.") String unavailableReason,
		String defaultProvider, List<String> fallbackProviders, String setupGuideUrl, LimitsDTO limits,
		CacheDTO cache, List<FeatureRouteDTO> features, List<ProviderDTO> providers) {

	public record LimitsDTO(int maxInputTokens, int maxOutputTokens, int maxCallsPerAnalysis, int timeoutSeconds,
			int maxRetries, double charsPerToken) {
	}

	public record CacheDTO(boolean enabled, Duration ttl) {
	}

	public record FeatureRouteDTO(String feature, String provider, String model) {
	}

	public record ProviderDTO(String id,
			@Schema(description = "ai.providers.<id>.enabled") boolean enabled,
			@Schema(description = "True when the provider bean exists (AI enabled and the provider enabled).") boolean active,
			DataHandling dataHandling,
			@Schema(description = "Hosted providers: whether the operator acknowledged the data handling.") boolean dataHandlingAcknowledged,
			@Schema(description = "Whether it may be used for a public repository (hosted needs the acknowledgement).") boolean permitted,
			String chatModel, boolean apiKeyConfigured, ProviderHealth health, AIProviderCapabilities capabilities,
			boolean pricingConfigured, String currency) {
	}

}
