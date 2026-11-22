package com.repointelligence.ai.provider;

import java.util.List;

/**
 * Which provider (and model) a feature request goes to, or why none could be used.
 *
 * @param service null when no permitted provider is available
 * @param model model override from the feature route, or null for the provider's configured model
 * @param requestedProvider the provider the request was meant for (preference, feature route or default)
 * @param fallback true when {@code service} is not the requested provider
 * @param reasons one line per provider that was skipped, in the order they were considered
 */
public record RoutingDecision(AIModelService service, String model, String requestedProvider, boolean fallback,
		List<String> reasons) {

	public boolean routed() {
		return service != null;
	}

	/** One sentence for the user when nothing could be routed. */
	public String unavailableReason() {
		if (reasons.isEmpty()) {
			return "AI insights are unavailable: no AI provider is configured.";
		}
		return "AI insights are unavailable: " + String.join("; ", reasons) + ".";
	}

}
