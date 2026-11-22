package com.repointelligence.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AIProviderRouterTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T10:00:00Z"));

	private final FakeModelService ollama = new FakeModelService("ollama", DataHandling.LOCAL);

	private final FakeModelService gemini = new FakeModelService("gemini", DataHandling.HOSTED);

	private final FakeModelService openrouter = new FakeModelService("openrouter", DataHandling.HOSTED);

	@Test
	void selectsTheConfiguredDefaultProvider() {
		AIProviderRouter router = router(props(true, "ollama", List.of(), Map.of(), false), ollama, gemini);

		RoutingDecision decision = router.route(new RoutingContext("explain-class", null, true));

		assertThat(decision.routed()).isTrue();
		assertThat(decision.service().getProviderId()).isEqualTo("ollama");
		assertThat(decision.fallback()).isFalse();
		assertThat(decision.model()).isNull();
	}

	@Test
	void routesAFeatureToItsConfiguredProviderAndModel() {
		Map<String, AIProperties.FeatureRoute> features = Map.of(
				"summarize-module", new AIProperties.FeatureRoute(null, "small-local-model"),
				"tech-debt-summary", new AIProperties.FeatureRoute("gemini", "strong-model"));
		AIProviderRouter router = router(props(true, "ollama", List.of(), features, true), ollama, gemini);

		RoutingDecision summary = router.route(new RoutingContext("summarize-module", null, true));
		RoutingDecision debt = router.route(new RoutingContext("tech-debt-summary", null, true));
		RoutingDecision other = router.route(new RoutingContext("explain-class", null, true));

		assertThat(summary.service().getProviderId()).isEqualTo("ollama");
		assertThat(summary.model()).isEqualTo("small-local-model");
		assertThat(debt.service().getProviderId()).isEqualTo("gemini");
		assertThat(debt.model()).isEqualTo("strong-model");
		assertThat(other.service().getProviderId()).isEqualTo("ollama");
		assertThat(other.model()).isNull();
	}

	@Test
	void theUsersPreferredProviderWinsWhenItIsPermitted() {
		AIProviderRouter router = router(props(true, "ollama", List.of(), Map.of(), true), ollama, gemini);

		assertThat(router.route(new RoutingContext("explain-class", "gemini", true)).service().getProviderId())
			.isEqualTo("gemini");
	}

	@Test
	void fallsBackOnlyToPermittedProviders() {
		ollama.health(ProviderHealth.Status.DOWN);
		// gemini acknowledged, openrouter not: the chain skips openrouter
		AIProviderRouter router = router(props(true, "ollama", List.of("openrouter", "gemini"), Map.of(), false, "gemini"),
				ollama, gemini, openrouter);

		RoutingDecision decision = router.route(new RoutingContext("explain-class", null, true));

		assertThat(decision.service().getProviderId()).isEqualTo("gemini");
		assertThat(decision.fallback()).isTrue();
		assertThat(decision.requestedProvider()).isEqualTo("ollama");
		assertThat(decision.reasons()).hasSize(2);
		assertThat(decision.reasons().get(0)).startsWith("ollama is down");
		assertThat(decision.reasons().get(1)).contains("openrouter").contains("not acknowledged");
		assertThat(openrouter.healthChecks).isZero();
	}

	@Test
	void aLocalProviderIsNeverReplacedByAHostedOneWithoutAcknowledgement() {
		ollama.health(ProviderHealth.Status.DOWN);
		AIProviderRouter router = router(props(true, "ollama", List.of("gemini", "openrouter"), Map.of(), false),
				ollama, gemini, openrouter);

		RoutingDecision decision = router.route(new RoutingContext("explain-class", null, true));

		assertThat(decision.routed()).isFalse();
		assertThat(gemini.healthChecks).isZero();
		assertThat(openrouter.healthChecks).isZero();
		assertThat(decision.unavailableReason()).contains("gemini is a hosted provider and its data handling is not acknowledged");
	}

	@Test
	void anAcknowledgedHostedProviderIsStillRefusedWhenTheRepositoryPolicyForbidsHostedProcessing() {
		ollama.health(ProviderHealth.Status.DOWN);
		AIProviderRouter router = router(props(true, "ollama", List.of("gemini"), Map.of(), true), ollama, gemini);

		RoutingDecision decision = router.route(new RoutingContext("explain-class", null, false));

		assertThat(decision.routed()).isFalse();
		assertThat(decision.unavailableReason()).contains("policy does not permit hosted processing");
	}

	@Test
	void aHostedDefaultProviderAlsoNeedsAcknowledgement() {
		AIProviderRouter router = router(props(true, "gemini", List.of(), Map.of(), false), gemini);

		assertThat(router.route(new RoutingContext("explain-class", null, true)).routed()).isFalse();
		assertThat(router.permitted("gemini", true)).isFalse();
	}

	@Test
	void noProviderConfiguredGivesAnUnavailableDecisionNotAnException() {
		AIProviderRouter router = router(props(true, "ollama", List.of(), Map.of(), false));

		RoutingDecision decision = router.route(new RoutingContext("explain-class", null, true));

		assertThat(decision.routed()).isFalse();
		assertThat(decision.unavailableReason()).isEqualTo("AI insights are unavailable: ollama is not enabled.");
	}

	@Test
	void aiDisabledGivesAnUnavailableDecisionWithoutTouchingProviders() {
		AIProviderRouter router = router(props(false, "ollama", List.of(), Map.of(), false), ollama);

		RoutingDecision decision = router.route(new RoutingContext("explain-class", null, true));

		assertThat(decision.routed()).isFalse();
		assertThat(decision.unavailableReason()).contains("AI is disabled");
		assertThat(ollama.healthChecks).isZero();
	}

	@Test
	void aRateLimitedProviderIsSkippedUntilItsResetTime() {
		AIProviderRouter router = router(props(true, "ollama", List.of(), Map.of(), false), ollama);
		router.availability().markRateLimited("ollama", clock.instant().plusSeconds(30));

		assertThat(router.route(new RoutingContext("explain-class", null, true)).unavailableReason())
			.contains("rate limited");
		assertThat(ollama.healthChecks).isZero();

		clock.advance(Duration.ofSeconds(31));
		assertThat(router.route(new RoutingContext("explain-class", null, true)).routed()).isTrue();
	}

	@Test
	void aRateLimitWithoutAResetTimeUsesTheCooldown() {
		ProviderAvailability availability = new ProviderAvailability(clock, Duration.ofSeconds(30), Duration.ofSeconds(60));

		Instant until = availability.markRateLimited("gemini", null);

		assertThat(until).isEqualTo(clock.instant().plusSeconds(60));
	}

	@Test
	void healthChecksAreCachedForTheConfiguredTtl() {
		AIProviderRouter router = router(props(true, "ollama", List.of(), Map.of(), false), ollama);

		router.route(new RoutingContext("explain-class", null, true));
		router.route(new RoutingContext("explain-class", null, true));
		assertThat(ollama.healthChecks).isEqualTo(1);

		clock.advance(Duration.ofSeconds(31));
		router.route(new RoutingContext("explain-class", null, true));
		assertThat(ollama.healthChecks).isEqualTo(2);
	}

	private AIProviderRouter router(AIProperties properties, AIModelService... services) {
		return new AIProviderRouter(properties, List.of(services),
				new ProviderAvailability(clock, properties.healthCacheTtl(), properties.rateLimitCooldown()));
	}

	/** Every hosted provider is acknowledged when {@code acknowledgeHosted}, else only those listed. */
	static AIProperties props(boolean enabled, String defaultProvider, List<String> fallbacks,
			Map<String, AIProperties.FeatureRoute> features, boolean acknowledgeHosted, String... acknowledged) {
		Map<String, AIProperties.ProviderSettings> providers = new HashMap<>();
		for (String id : List.of("ollama", "gemini", "openrouter")) {
			boolean ack = acknowledgeHosted || List.of(acknowledged).contains(id);
			providers.put(id, new AIProperties.ProviderSettings(true, "http://localhost", "k", id + "-model", ack, List.of(),
					null, null, null, null, null, null, null, null));
		}
		return new AIProperties(enabled, defaultProvider, fallbacks, features, providers,
				new AIProperties.Limits(12000, 1500, 10, 45, 1), new AIProperties.Cache(true, Duration.ofDays(7)),
				new AIProperties.TokenEstimate(4), new AIProperties.Source(true, 400), Duration.ofSeconds(30), Duration.ofSeconds(60), false, null,
					new AIProperties.Embedding("ollama", 16, 6000, 250));
	}

}
