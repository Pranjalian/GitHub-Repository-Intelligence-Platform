package com.repointelligence.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which providers are temporarily unusable. A rate-limited provider is skipped until its reset
 * time (or the configured cooldown), so a quota error never turns into a retry storm. Health checks are
 * reused for {@code ai.health-cache-ttl} so routing does not probe a provider on every request.
 */
public class ProviderAvailability {

	private final Clock clock;

	private final Duration healthTtl;

	private final Duration defaultCooldown;

	private final Map<String, Instant> rateLimitedUntil = new ConcurrentHashMap<>();

	private final Map<String, ProviderHealth> healthCache = new ConcurrentHashMap<>();

	public ProviderAvailability(Clock clock, Duration healthTtl, Duration defaultCooldown) {
		this.clock = clock;
		this.healthTtl = healthTtl;
		this.defaultCooldown = defaultCooldown;
	}

	/** Marks a provider unusable until {@code until}, or for the default cooldown when the provider gave no time. */
	public Instant markRateLimited(String providerId, Instant until) {
		Instant now = clock.instant();
		Instant effective = until != null && until.isAfter(now) ? until : now.plus(defaultCooldown);
		rateLimitedUntil.merge(providerId, effective, (a, b) -> a.isAfter(b) ? a : b);
		healthCache.remove(providerId);
		return rateLimitedUntil.get(providerId);
	}

	/** The reset time while a rate limit is in force, else null. */
	public Instant rateLimitedUntil(String providerId) {
		Instant until = rateLimitedUntil.get(providerId);
		if (until == null) {
			return null;
		}
		if (!until.isAfter(clock.instant())) {
			rateLimitedUntil.remove(providerId, until);
			return null;
		}
		return until;
	}

	/** Current health: rate-limit state first, then a cached or fresh health check. */
	public ProviderHealth health(AIModelService service) {
		String id = service.getProviderId();
		Instant until = rateLimitedUntil(id);
		Instant now = clock.instant();
		if (until != null) {
			return new ProviderHealth(ProviderHealth.Status.RATE_LIMITED, "Rate limit or quota reached; skipped until " + until + ".",
					now, until);
		}
		ProviderHealth cached = healthCache.get(id);
		if (cached != null && cached.checkedAt() != null && cached.checkedAt().plus(healthTtl).isAfter(now)) {
			return cached;
		}
		ProviderHealth fresh;
		try {
			fresh = service.health();
		}
		catch (RuntimeException ex) {
			fresh = ProviderHealth.down("Health check failed (" + ex.getClass().getSimpleName() + ").", now);
		}
		if (fresh == null) {
			fresh = ProviderHealth.down("Health check returned nothing.", now);
		}
		// stamped with this clock, so the cache TTL never depends on the provider's notion of time
		fresh = new ProviderHealth(fresh.status(), fresh.detail(), now, fresh.unavailableUntil());
		healthCache.put(id, fresh);
		return fresh;
	}

	/** Lifts a rate limit early (tests, or an operator who knows the quota was reset). */
	public void clearRateLimit(String providerId) {
		rateLimitedUntil.remove(providerId);
	}

	/** Forgets a cached health result, e.g. after a call failed with a connection error. */
	public void invalidate(String providerId) {
		healthCache.remove(providerId);
	}

}
