package com.repointelligence.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects the embedding provider ({@code ai.embedding.provider}). There is deliberately no fallback:
 * vectors of different models are not comparable, so an index is built and queried with exactly one
 * model and version. A hosted provider needs the same acknowledgement and repository policy as for chat.
 * Health checks (which also read the model version) are cached for {@code ai.health-cache-ttl}; rate
 * limits are shared with chat through {@link ProviderAvailability}, because quotas are per provider.
 */
public class EmbeddingRouter {

	private final AIProperties properties;

	private final Map<String, EmbeddingModelService> services = new LinkedHashMap<>();

	private final ProviderAvailability availability;

	private final Clock clock;

	private final Duration healthTtl;

	private final Map<String, EmbeddingHealth> healthCache = new ConcurrentHashMap<>();

	public EmbeddingRouter(AIProperties properties, List<EmbeddingModelService> services,
			ProviderAvailability availability, Clock clock) {
		this.properties = properties;
		this.availability = availability;
		this.clock = clock;
		this.healthTtl = properties.healthCacheTtl();
		services.forEach(s -> this.services.put(s.getProviderId(), s));
	}

	/**
	 * The outcome of a selection: a service with the model and version to use, or the reason there is none.
	 *
	 * @param notConfigured true when embeddings are simply not set up (as opposed to configured but failing)
	 */
	public record Selection(EmbeddingModelService service, String provider, String model, String modelVersion,
			String reason, boolean notConfigured) {

		public boolean available() {
			return service != null;
		}

	}

	public Selection select(boolean hostedPermitted) {
		String id = properties.embedding().provider();
		if (!properties.enabled()) {
			return new Selection(null, id, null, null, "AI is disabled (ai.enabled=false), so no embeddings are made",
					true);
		}
		EmbeddingModelService service = services.get(id);
		if (service == null) {
			return new Selection(null, id, null, null, "the embedding provider " + id + " is not enabled", true);
		}
		if (service.embeddingModel() == null || service.embeddingModel().isBlank()) {
			return new Selection(null, id, null, null,
					"no embedding model is configured (ai.providers." + id + ".embedding-model)", true);
		}
		if (service.dataHandling() == DataHandling.HOSTED) {
			AIProperties.ProviderSettings settings = properties.provider(id);
			if (settings == null || !settings.dataHandlingAcknowledged()) {
				return new Selection(null, id, service.embeddingModel(), null, id
						+ " is a hosted provider and its data handling is not acknowledged (ai.providers." + id
						+ ".data-handling-acknowledged=false)", true);
			}
			if (!hostedPermitted) {
				return new Selection(null, id, service.embeddingModel(), null,
						id + " is a hosted provider and this repository's policy does not permit hosted processing", true);
			}
		}
		EmbeddingHealth health = health(service);
		if (!health.isUp()) {
			ProviderHealth h = health.health();
			String state = h == null ? "unavailable"
					: h.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
			return new Selection(null, id, service.embeddingModel(), null, "the embedding model of " + id + " is " + state
					+ (h == null || h.detail() == null ? "" : " (" + h.detail() + ")"), false);
		}
		return new Selection(service, id, health.model(), health.modelVersion(), null, false);
	}

	/** Current embedding health, rate-limit state first. */
	public EmbeddingHealth health(EmbeddingModelService service) {
		String id = service.getProviderId();
		Instant now = clock.instant();
		Instant until = availability.rateLimitedUntil(id);
		if (until != null) {
			return new EmbeddingHealth(new ProviderHealth(ProviderHealth.Status.RATE_LIMITED,
					"Rate limit or quota reached; skipped until " + until + ".", now, until), service.embeddingModel(), null);
		}
		EmbeddingHealth cached = healthCache.get(id);
		if (cached != null && cached.health() != null && cached.health().checkedAt() != null
				&& cached.health().checkedAt().plus(healthTtl).isAfter(now)) {
			return cached;
		}
		EmbeddingHealth fresh;
		try {
			fresh = service.embeddingHealth();
		}
		catch (RuntimeException ex) {
			fresh = null;
		}
		if (fresh == null || fresh.health() == null) {
			fresh = new EmbeddingHealth(ProviderHealth.down("Embedding health check failed.", now), service.embeddingModel(),
					null);
		}
		ProviderHealth h = fresh.health();
		fresh = new EmbeddingHealth(new ProviderHealth(h.status(), h.detail(), now, h.unavailableUntil()), fresh.model(),
				fresh.modelVersion());
		healthCache.put(id, fresh);
		return fresh;
	}

	/** Records the outcome of a call: rate limits pause the provider, other failures force a new health check. */
	public void report(String providerId, EmbeddingResponse response) {
		if (response.status() == AIStatus.RATE_LIMITED) {
			availability.markRateLimited(providerId, response.retryAfter());
			healthCache.remove(providerId);
		}
		else if (!response.isOk()) {
			healthCache.remove(providerId);
		}
	}

	public EmbeddingModelService service(String id) {
		return services.get(id);
	}

}
