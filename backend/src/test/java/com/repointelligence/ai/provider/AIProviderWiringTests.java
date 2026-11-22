package com.repointelligence.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.ai.provider.gemini.GeminiAIModelService;
import com.repointelligence.ai.provider.ollama.OllamaAIModelService;
import com.repointelligence.ai.provider.openrouter.OpenRouterAIModelService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Provider beans exist only when {@code ai.enabled} and the provider's own flag are both true. */
class AIProviderWiringTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withBean(Clock.class, Clock::systemUTC)
		.withUserConfiguration(AIProviderConfig.class, OllamaAIModelService.class, GeminiAIModelService.class,
				OpenRouterAIModelService.class);

	@Test
	void byDefaultNoProviderBeanExistsAndTheRouterSaysUnavailable() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(AIModelService.class)).isEmpty();
			AIProviderRouter router = context.getBean(AIProviderRouter.class);
			assertThat(router.services()).isEmpty();
			assertThat(router.route(new RoutingContext("explain-class", null, true)).unavailableReason())
				.contains("AI is disabled");
		});
	}

	@Test
	void aProviderFlagAloneDoesNotCreateABeanWhileAiIsDisabled() {
		runner.withPropertyValues("ai.providers.ollama.enabled=true", "ai.providers.gemini.enabled=true")
			.run(context -> assertThat(context.getBeansOfType(AIModelService.class)).isEmpty());
	}

	@Test
	void onlyEnabledProvidersAreCreated() {
		runner.withPropertyValues("ai.enabled=true", "ai.providers.ollama.enabled=true",
				"ai.providers.ollama.chat-model=llama3.2")
			.run(context -> {
				assertThat(context.getBeansOfType(AIModelService.class)).hasSize(1);
				assertThat(context).hasSingleBean(OllamaAIModelService.class);
				assertThat(context).doesNotHaveBean(GeminiAIModelService.class);
				assertThat(context.getBean(AIProviderRouter.class).services()).extracting(AIModelService::getProviderId)
					.containsExactly("ollama");
			});
	}

	@Test
	void theApiKeyIsNeverPartOfTheSettingsText() {
		runner.withPropertyValues("ai.providers.gemini.api-key=very-secret-value")
			.run(context -> assertThat(context.getBean(AIProperties.class).provider("gemini").toString())
				.doesNotContain("very-secret-value")
				.contains("apiKey=set"));
	}

}
