package com.repointelligence.ai.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects the provider and model for a feature request.
 * <ul>
 * <li>Order: the user's preferred provider, else the feature route's provider, else
 * {@code ai.default-provider}; then {@code ai.fallback-providers} in order.</li>
 * <li>A hosted provider is used only when {@code ai.providers.<id>.data-handling-acknowledged=true} and the
 * repository's policy permits hosted processing. So a local provider is never silently replaced by a
 * hosted one: the switch needs both settings and is reported as a fallback.</li>
 * <li>Rate-limited providers are skipped until their reset time; unhealthy ones are skipped.</li>
 * <li>With AI disabled or nothing configured the decision is "not routed" with a reason, never an exception.</li>
 * </ul>
 */
public class AIProviderRouter {

	private final AIProperties properties;

	private final Map<String, AIModelService> services = new LinkedHashMap<>();

	private final ProviderAvailability availability;

	public AIProviderRouter(AIProperties properties, List<AIModelService> services, ProviderAvailability availability) {
		this.properties = properties;
		this.availability = availability;
		services.forEach(s -> this.services.put(s.getProviderId(), s));
	}

	public RoutingDecision route(RoutingContext context) {
		List<String> reasons = new ArrayList<>();
		AIProperties.FeatureRoute route = properties.route(context.feature());
		String requested = firstNonBlank(context.preferredProvider(), route.provider(), properties.defaultProvider());
		if (!properties.enabled()) {
			reasons.add("AI is disabled (ai.enabled=false)");
			return new RoutingDecision(null, null, requested, false, reasons);
		}
		Set<String> chain = new LinkedHashSet<>();
		if (requested != null) {
			chain.add(requested);
		}
		chain.addAll(properties.fallbackProviders());
		for (String id : chain) {
			AIModelService service = services.get(id);
			if (service == null) {
				reasons.add(id + " is not enabled");
				continue;
			}
			String refusal = refusal(service, context);
			if (refusal != null) {
				reasons.add(id + " " + refusal);
				continue;
			}
			ProviderHealth health = availability.health(service);
			if (!health.isUp()) {
				reasons.add(id + " is " + health.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
						+ (health.detail() == null ? "" : " (" + health.detail() + ")"));
				continue;
			}
			String model = route.model() != null && id.equals(firstNonBlank(route.provider(), properties.defaultProvider()))
					? route.model() : null;
			return new RoutingDecision(service, model, requested, !id.equals(requested), reasons);
		}
		return new RoutingDecision(null, null, requested, false, reasons);
	}

	/** Why a provider must not be used for this request, or null when it is permitted. */
	public String refusal(AIModelService service, RoutingContext context) {
		if (service.dataHandling() != DataHandling.HOSTED) {
			return null;
		}
		AIProperties.ProviderSettings settings = properties.provider(service.getProviderId());
		if (settings == null || !settings.dataHandlingAcknowledged()) {
			return "is a hosted provider and its data handling is not acknowledged (ai.providers."
					+ service.getProviderId() + ".data-handling-acknowledged=false)";
		}
		if (!context.hostedPermitted()) {
			return "is a hosted provider and this repository's policy does not permit hosted processing";
		}
		return null;
	}

	/** Whether a provider would be permitted for a repository with the given policy (health not checked). */
	public boolean permitted(String providerId, boolean hostedPermitted) {
		AIModelService service = services.get(providerId);
		return service != null && refusal(service, new RoutingContext(null, null, hostedPermitted)) == null;
	}

	public List<AIModelService> services() {
		return List.copyOf(services.values());
	}

	public AIModelService service(String id) {
		return services.get(id);
	}

	public ProviderAvailability availability() {
		return availability;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

}
