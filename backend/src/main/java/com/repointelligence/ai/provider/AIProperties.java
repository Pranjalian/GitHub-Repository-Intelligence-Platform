package com.repointelligence.ai.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ai.*}. Everything is off by default: with {@code ai.enabled=false} no provider bean and no
 * provider HTTP client is created, and every AI feature answers "unavailable" instead of failing.
 * Model names, context sizes and prices come from configuration only; nothing assumes that a provider
 * or model is free.
 *
 * @param enabled master switch
 * @param defaultProvider provider used when neither the request nor the feature route names one
 * @param fallbackProviders tried in order when the selected provider is unavailable; a hosted provider is
 * only ever used when its data handling is acknowledged and the repository permits hosted processing
 * @param features per-feature routing (provider and/or model), keyed by feature id such as
 * {@code explain-class}
 * @param providers provider settings keyed by provider id ({@code ollama}, {@code gemini}, {@code openrouter})
 * @param limits call, token, time and retry limits
 * @param cache response cache
 * @param tokenEstimate heuristic used when a provider does not report token usage
 * @param healthCacheTtl how long a provider health check is reused
 * @param rateLimitCooldown how long a rate-limited provider is skipped when it gives no reset time
 * @param allowHostedForPrivateRepositories whether hosted providers may process non-public repositories
 * (only public repositories can be analysed today, so this only matters later)
 * @param setupGuideUrl link shown in the UI when AI is unavailable
 */
@ConfigurationProperties("ai")
public record AIProperties(@DefaultValue("false") boolean enabled, @DefaultValue("ollama") String defaultProvider,
		List<String> fallbackProviders, Map<String, FeatureRoute> features,
		Map<String, ProviderSettings> providers, @DefaultValue Limits limits, @DefaultValue Cache cache,
		@DefaultValue TokenEstimate tokenEstimate, @DefaultValue("PT30S") Duration healthCacheTtl,
		@DefaultValue("PT60S") Duration rateLimitCooldown,
		@DefaultValue("false") boolean allowHostedForPrivateRepositories, String setupGuideUrl) {

	public AIProperties {
		features = features == null ? Map.of() : Map.copyOf(features);
		providers = providers == null ? Map.of() : Map.copyOf(providers);
		fallbackProviders = fallbackProviders == null ? List.of() : List.copyOf(fallbackProviders);
	}

	/** Settings of one provider; {@code null} when the provider is not configured at all. */
	public ProviderSettings provider(String id) {
		return providers.get(id);
	}

	/** The route of a feature, never null. */
	public FeatureRoute route(String feature) {
		FeatureRoute route = feature == null ? null : features.get(feature);
		return route == null ? new FeatureRoute(null, null) : route;
	}

	/** Per-feature routing: a smaller or local model for simple summaries, a stronger one elsewhere. */
	public record FeatureRoute(String provider, String model) {
	}

	/**
	 * @param enabled the provider bean is only created when this and {@code ai.enabled} are true
	 * @param baseUrl provider API root
	 * @param apiKey hosted providers only; read from the environment, never logged or returned
	 * @param chatModel model used unless a feature route names another
	 * @param dataHandlingAcknowledged hosted providers: the operator has read the provider's current terms and
	 * accepts that repository excerpts are sent to it. Without it the provider is never used
	 * @param allowedHosts outbound allowlist for {@code baseUrl}
	 * @param maxContextTokens context window when the provider cannot report it
	 * @param structuredOutput whether to request JSON-schema output (null: use the runtime capability)
	 * @param inputPricePerMillionTokens price per million input tokens; cost stays null unless both prices are set
	 * @param outputPricePerMillionTokens price per million output tokens
	 * @param currency currency of the prices, for display
	 */
	public record ProviderSettings(boolean enabled, String baseUrl, String apiKey, String chatModel,
			boolean dataHandlingAcknowledged, List<String> allowedHosts, Integer maxContextTokens,
			Boolean structuredOutput, BigDecimal inputPricePerMillionTokens, BigDecimal outputPricePerMillionTokens,
			String currency) {

		public ProviderSettings {
			allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
		}

		public boolean pricingConfigured() {
			return inputPricePerMillionTokens != null && outputPricePerMillionTokens != null;
		}

		@Override
		public String toString() {
			// never print the key
			return "ProviderSettings[enabled=" + enabled + ", baseUrl=" + baseUrl + ", chatModel=" + chatModel
					+ ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "set") + "]";
		}

	}

	/**
	 * @param maxInputTokens prompt budget (estimated); evidence is trimmed to fit
	 * @param maxOutputTokens requested output limit
	 * @param maxCallsPerAnalysis provider calls allowed per analysis run of a snapshot (cache hits are free)
	 * @param timeoutSeconds per HTTP attempt
	 * @param maxRetries retries after a transient failure (connection error or 5xx); never unbounded
	 */
	public record Limits(@DefaultValue("12000") int maxInputTokens, @DefaultValue("1500") int maxOutputTokens,
			@DefaultValue("10") int maxCallsPerAnalysis, @DefaultValue("45") int timeoutSeconds,
			@DefaultValue("1") int maxRetries) {

		public Limits {
			if (maxRetries < 0 || maxRetries > 3) {
				throw new IllegalArgumentException("ai.limits.max-retries must be between 0 and 3");
			}
			if (timeoutSeconds < 1 || timeoutSeconds > 300) {
				throw new IllegalArgumentException("ai.limits.timeout-seconds must be between 1 and 300");
			}
		}

		public Duration timeout() {
			return Duration.ofSeconds(timeoutSeconds);
		}

	}

	public record Cache(@DefaultValue("true") boolean enabled, @DefaultValue("P7D") Duration ttl) {
	}

	public record TokenEstimate(@DefaultValue("4") double charsPerToken) {

		public TokenEstimate {
			if (charsPerToken <= 0) {
				throw new IllegalArgumentException("ai.token-estimate.chars-per-token must be positive");
			}
		}

	}

}
