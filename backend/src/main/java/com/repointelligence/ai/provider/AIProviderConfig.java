package com.repointelligence.ai.provider;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the router. Provider beans are conditional on {@code ai.enabled} and their own
 * {@code enabled} flag, so with AI disabled the router has no services and no HTTP client exists.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AIProperties.class)
public class AIProviderConfig {

	@Bean
	ProviderAvailability providerAvailability(AIProperties properties, Clock clock) {
		return new ProviderAvailability(clock, properties.healthCacheTtl(), properties.rateLimitCooldown());
	}

	@Bean
	AIProviderRouter aiProviderRouter(AIProperties properties, ObjectProvider<AIModelService> services,
			ProviderAvailability availability) {
		return new AIProviderRouter(properties, services.orderedStream().toList(), availability);
	}

	@Bean
	EmbeddingRouter embeddingRouter(AIProperties properties, ObjectProvider<EmbeddingModelService> services,
			ProviderAvailability availability, Clock clock) {
		return new EmbeddingRouter(properties, services.orderedStream().toList(), availability, clock);
	}

}
