package com.repointelligence.ai.provider;

import java.time.Instant;

/**
 * Result of a provider health check. {@code detail} never contains an API key or a response body.
 *
 * @param unavailableUntil set while a rate limit is in force
 */
public record ProviderHealth(Status status, String detail, Instant checkedAt, Instant unavailableUntil) {

	public enum Status {

		/** Reachable and the configured model is available. */
		UP,
		/** Reachable but unusable (unknown model, bad key) or not reachable. */
		DOWN,
		/** Enabled but missing settings (model, key). */
		NOT_CONFIGURED,
		/** Skipped until {@code unavailableUntil}. */
		RATE_LIMITED,
		/** Not checked (AI disabled or provider disabled). */
		UNKNOWN

	}

	public boolean isUp() {
		return status == Status.UP;
	}

	public static ProviderHealth up(String detail, Instant at) {
		return new ProviderHealth(Status.UP, detail, at, null);
	}

	public static ProviderHealth down(String detail, Instant at) {
		return new ProviderHealth(Status.DOWN, detail, at, null);
	}

	public static ProviderHealth notConfigured(String detail, Instant at) {
		return new ProviderHealth(Status.NOT_CONFIGURED, detail, at, null);
	}

}
