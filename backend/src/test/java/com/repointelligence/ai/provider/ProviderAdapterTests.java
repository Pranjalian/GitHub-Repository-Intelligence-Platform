package com.repointelligence.ai.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.repointelligence.ai.provider.gemini.GeminiAIModelService;
import com.repointelligence.ai.provider.ollama.OllamaAIModelService;
import com.repointelligence.ai.provider.openrouter.OpenRouterAIModelService;
import com.repointelligence.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Each adapter against a WireMock stand-in for its API: request format, authentication, usage mapping,
 * error mapping, and the timeout and retry limits of {@link ProviderHttp}.
 */
class ProviderAdapterTests {

	static final WireMockServer API = new WireMockServer(options().dynamicPort());

	/** Generous, so a slow CI machine never turns a status test into a timeout; the timeout test uses its own. */
	static final int TIMEOUT = 10;

	static final Clock CLOCK = new MutableClock(Instant.parse("2026-11-22T10:00:00Z"));

	static final Map<String, Object> SCHEMA = Map.of("type", "object", "required", List.of("summary"), "properties",
			Map.of("summary", Map.of("type", "string")));

	@BeforeAll
	static void start() {
		API.start();
	}

	@AfterAll
	static void stop() {
		API.stop();
	}

	@BeforeEach
	void reset() {
		API.resetAll();
	}

	static AIProperties properties(String id, String model, String key, int timeoutSeconds, int maxRetries) {
		return properties(id, model, key, timeoutSeconds, maxRetries, null);
	}

	static AIProperties properties(String id, String model, String key, int timeoutSeconds, int maxRetries,
			String thinkingLevel) {
		return properties(id, model, key, timeoutSeconds, maxRetries, thinkingLevel, null);
	}

	static AIProperties properties(String id, String model, String key, int timeoutSeconds, int maxRetries,
			String thinkingLevel, String embeddingModel) {
		AIProperties.ProviderSettings settings = new AIProperties.ProviderSettings(true, "http://localhost:" + API.port(),
				key, model, true, List.of("localhost"), null, null, null, null, null, false, thinkingLevel, embeddingModel);
		return new AIProperties(true, id, List.of(), Map.of(), Map.of(id, settings),
				new AIProperties.Limits(12000, 1500, 10, timeoutSeconds, maxRetries),
				new AIProperties.Cache(true, Duration.ofDays(7)), new AIProperties.TokenEstimate(4), new AIProperties.Source(true, 400), Duration.ofSeconds(30),
				Duration.ofSeconds(60), false, null,
					new AIProperties.Embedding("ollama", 16, 6000, 250));
	}

	static AIRequest request(Map<String, Object> schema) {
		return new AIRequest("SYSTEM TEXT", "USER TEXT", "explain-class.v1", 300, 0.2, schema, null, UUID.randomUUID(),
				UUID.randomUUID(), "explain-class");
	}

	static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
	}

	@Nested
	class Ollama {

		final OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "llama3.2", null, TIMEOUT, 1), CLOCK);

		@Test
		void sendsANonStreamingChatWithSystemAndUserMessagesAndTheSchemaAsFormat() {
			API.stubFor(post("/api/chat").willReturn(json(200,
					"{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\":\\\"ok\\\"}\"},"
							+ "\"done\":true,\"prompt_eval_count\":120,\"eval_count\":34}")));

			AIResponse response = service.generate(request(SCHEMA));

			assertThat(response.status()).isEqualTo(AIStatus.OK);
			assertThat(response.content()).isEqualTo("{\"summary\":\"ok\"}");
			assertThat(response.inputTokens()).isEqualTo(120);
			assertThat(response.outputTokens()).isEqualTo(34);
			assertThat(response.usageSource()).isEqualTo(UsageSource.REPORTED);
			assertThat(response.provider()).isEqualTo("ollama");
			API.verify(postRequestedFor(urlEqualTo("/api/chat"))
				.withRequestBody(matchingJsonPath("$.model", equalTo("llama3.2")))
				.withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
				.withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
				.withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("SYSTEM TEXT")))
				.withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
				.withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("USER TEXT")))
				.withRequestBody(matchingJsonPath("$.options.num_predict", equalTo("300")))
				.withRequestBody(matchingJsonPath("$.format.required[0]", equalTo("summary"))));
		}

		@Test
		void tokenCountsStayNullWhenOllamaDoesNotReportThem() {
			API.stubFor(post("/api/chat").willReturn(json(200, "{\"message\":{\"content\":\"hi\"},\"done\":true}")));

			AIResponse response = service.generate(request(null));

			assertThat(response.inputTokens()).isNull();
			assertThat(response.outputTokens()).isNull();
			assertThat(response.usageSource()).isEqualTo(UsageSource.UNKNOWN);
		}

		@Test
		void healthChecksTheModelListAndReportsAMissingModel() {
			API.stubFor(get("/api/tags").willReturn(json(200, "{\"models\":[{\"name\":\"llama3.2:latest\"}]}")));
			assertThat(service.health().status()).isEqualTo(ProviderHealth.Status.UP);

			OllamaAIModelService other = new OllamaAIModelService(properties("ollama", "qwen2.5", null, TIMEOUT, 1), CLOCK);
			ProviderHealth health = other.health();
			assertThat(health.status()).isEqualTo(ProviderHealth.Status.DOWN);
			assertThat(health.detail()).contains("ollama pull qwen2.5");
		}

		@Test
		void aMissingModelNameIsNotConfiguredAndNothingIsSent() {
			OllamaAIModelService unconfigured = new OllamaAIModelService(properties("ollama", "", null, TIMEOUT, 1), CLOCK);

			assertThat(unconfigured.health().status()).isEqualTo(ProviderHealth.Status.NOT_CONFIGURED);
			assertThat(unconfigured.generate(request(null)).status()).isEqualTo(AIStatus.UNAVAILABLE);
			assertThat(API.getAllServeEvents()).isEmpty();
		}

		@Test
		void anUnreachableServerIsUnavailable() {
			API.stop();
			try {
				AIResponse response = service.generate(request(null));
				assertThat(response.status()).isEqualTo(AIStatus.UNAVAILABLE);
				assertThat(response.detail()).contains("not reachable");
			}
			finally {
				API.start();
			}
		}

	}

	@Nested
	class Gemini {

		final GeminiAIModelService service = new GeminiAIModelService(
				properties("gemini", "gemini-test", "gm-test-key", TIMEOUT, 1), CLOCK);

		@Test
		void sendsSystemInstructionAndContentsWithTheKeyInAHeaderAndMapsReportedUsage() {
			API.stubFor(post("/v1beta/models/gemini-test:generateContent").willReturn(json(200,
					"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\"},{\"text\":\"\\\"x\\\"}\"}]},"
							+ "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":900,"
							+ "\"candidatesTokenCount\":80,\"totalTokenCount\":980},\"modelVersion\":\"gemini-test-001\"}")));

			AIResponse response = service.generate(request(SCHEMA));

			assertThat(response.status()).isEqualTo(AIStatus.OK);
			assertThat(response.content()).isEqualTo("{\"summary\":\"x\"}");
			assertThat(response.inputTokens()).isEqualTo(900);
			assertThat(response.outputTokens()).isEqualTo(80);
			assertThat(response.usageSource()).isEqualTo(UsageSource.REPORTED);
			assertThat(response.model()).isEqualTo("gemini-test-001");
			API.verify(postRequestedFor(urlEqualTo("/v1beta/models/gemini-test:generateContent"))
				.withHeader("x-goog-api-key", equalTo("gm-test-key"))
				.withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("SYSTEM TEXT")))
				.withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
				.withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", equalTo("USER TEXT")))
				// the answer's 300 tokens plus the thinking allowance, which Gemini counts against the same limit
				.withRequestBody(matchingJsonPath("$.generationConfig.maxOutputTokens", equalTo("2348")))
				.withRequestBody(matchingJsonPath("$.generationConfig.responseMimeType", equalTo("application/json"))));
			assertThat(API.getAllServeEvents().get(0).getRequest().getUrl()).doesNotContain("key");
			// no thinking level configured: nothing sent, so models without thinking support still work
			assertThat(API.getAllServeEvents().get(0).getRequest().getBodyAsString()).doesNotContain("thinkingConfig");
		}

		@Test
		void sendsTheConfiguredThinkingLevelAndCountsThinkingTokensAsOutput() {
			GeminiAIModelService thinking = new GeminiAIModelService(
					properties("gemini", "gemini-test", "gm-test-key", TIMEOUT, 1, "low"), CLOCK);
			API.stubFor(post("/v1beta/models/gemini-test:generateContent").willReturn(json(200,
					"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]},\"finishReason\":\"STOP\"}],"
							+ "\"usageMetadata\":{\"promptTokenCount\":900,\"candidatesTokenCount\":80,"
							+ "\"thoughtsTokenCount\":120}}")));

			AIResponse response = thinking.generate(request(SCHEMA));

			assertThat(response.status()).isEqualTo(AIStatus.OK);
			assertThat(response.outputTokens()).as("answer + thinking, as Gemini bills them").isEqualTo(200);
			API.verify(postRequestedFor(urlEqualTo("/v1beta/models/gemini-test:generateContent"))
				.withRequestBody(matchingJsonPath("$.generationConfig.thinkingConfig.thinkingLevel", equalTo("low"))));
		}

		@Test
		void aBlankThinkingLevelIsNotSent() {
			GeminiAIModelService blank = new GeminiAIModelService(
					properties("gemini", "gemini-test", "gm-test-key", TIMEOUT, 1, "  "), CLOCK);
			API.stubFor(post("/v1beta/models/gemini-test:generateContent").willReturn(json(200,
					"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}]}")));

			assertThat(blank.generate(request(null)).status()).isEqualTo(AIStatus.OK);
			assertThat(API.getAllServeEvents().get(0).getRequest().getBodyAsString()).doesNotContain("thinkingConfig");
		}

		@Test
		void anAnswerCutOffAtTheOutputLimitIsInvalidOutputWithTheReason() {
			API.stubFor(post("/v1beta/models/gemini-test:generateContent").willReturn(json(200,
					"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\\\"half\"}]},"
							+ "\"finishReason\":\"MAX_TOKENS\"}]}")));

			AIResponse response = service.generate(request(SCHEMA));

			assertThat(response.status()).isEqualTo(AIStatus.INVALID_OUTPUT);
			assertThat(response.detail()).contains("output limit").contains("300 answer tokens");
		}

		@Test
		void aRejectedThinkingLevelPointsAtTheSetting() {
			GeminiAIModelService thinking = new GeminiAIModelService(
					properties("gemini", "gemini-test", "gm-test-key", TIMEOUT, 1, "bogus"), CLOCK);
			API.stubFor(post("/v1beta/models/gemini-test:generateContent")
				.willReturn(json(400, "{\"error\":{\"message\":\"Invalid thinking level\"}}")));

			AIResponse response = thinking.generate(request(null));

			assertThat(response.status()).isEqualTo(AIStatus.ERROR);
			assertThat(response.detail()).contains("HTTP 400").contains("GEMINI_THINKING_LEVEL");
		}

		@Test
		void quotaErrorsAreRateLimitedWithTheResetFromRetryInfoAndAreNotRetried() {
			API.stubFor(post("/v1beta/models/gemini-test:generateContent").willReturn(json(429,
					"{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":"
							+ "\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"17s\"}]}}")));

			AIResponse response = service.generate(request(null));

			assertThat(response.status()).isEqualTo(AIStatus.RATE_LIMITED);
			assertThat(response.retryAfter()).isEqualTo(CLOCK.instant().plusSeconds(17));
			API.verify(1, postRequestedFor(urlEqualTo("/v1beta/models/gemini-test:generateContent")));
		}

		@Test
		void aRejectedKeyIsAnErrorWithoutTheResponseBodyOrKey() {
			API.stubFor(post("/v1beta/models/gemini-test:generateContent")
				.willReturn(json(400, "{\"error\":{\"message\":\"API key not valid gm-test-key\"}}")));

			AIResponse response = service.generate(request(null));

			assertThat(response.status()).isEqualTo(AIStatus.ERROR);
			assertThat(response.detail()).contains("HTTP 400").doesNotContain("gm-test-key").doesNotContain("not valid");
		}

		@Test
		void aBlockedPromptIsAnError() {
			API.stubFor(post("/v1beta/models/gemini-test:generateContent")
				.willReturn(json(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")));

			assertThat(service.generate(request(null)).detail()).contains("blocked: SAFETY");
		}

		@Test
		void healthReadsTheContextWindowAtRuntime() {
			API.stubFor(get("/v1beta/models/gemini-test")
				.willReturn(json(200, "{\"name\":\"models/gemini-test\",\"inputTokenLimit\":32768}")));

			assertThat(service.health().status()).isEqualTo(ProviderHealth.Status.UP);
			assertThat(service.getCapabilities().maxContextTokens()).isEqualTo(32768);
			assertThat(service.getCapabilities().source()).isEqualTo("RUNTIME");
			API.verify(getRequestedFor(urlEqualTo("/v1beta/models/gemini-test")).withHeader("x-goog-api-key",
					equalTo("gm-test-key")));
		}

		@Test
		void withoutAKeyNothingIsSent() {
			GeminiAIModelService unkeyed = new GeminiAIModelService(properties("gemini", "gemini-test", "", TIMEOUT, 1), CLOCK);

			assertThat(unkeyed.health().status()).isEqualTo(ProviderHealth.Status.NOT_CONFIGURED);
			assertThat(unkeyed.generate(request(null)).status()).isEqualTo(AIStatus.UNAVAILABLE);
			assertThat(API.getAllServeEvents()).isEmpty();
		}

		@Test
		void hostedProvidersRequireHttpsByDefault() {
			AIProperties.ProviderSettings plain = new AIProperties.ProviderSettings(true, "http://localhost:" + API.port(),
					"k", "gemini-test", true, List.of("localhost"), null, null, null, null, null, null, null, null);
			AIProperties props = new AIProperties(true, "gemini", List.of(), Map.of(), Map.of("gemini", plain),
					new AIProperties.Limits(12000, 1500, 10, TIMEOUT, 1), new AIProperties.Cache(true, Duration.ofDays(7)),
					new AIProperties.TokenEstimate(4), new AIProperties.Source(true, 400), Duration.ofSeconds(30), Duration.ofSeconds(60), false, null,
					new AIProperties.Embedding("ollama", 16, 6000, 250));

			ProviderHealth health = new GeminiAIModelService(props, CLOCK).health();

			assertThat(health.status()).isEqualTo(ProviderHealth.Status.NOT_CONFIGURED);
			assertThat(health.detail()).contains("https");
		}

		@Test
		void aHostOutsideTheAllowlistIsRefused() {
			AIProperties.ProviderSettings elsewhere = new AIProperties.ProviderSettings(true, "https://evil.example", "k",
					"gemini-test", true, List.of(), null, null, null, null, null, null, null, null);
			AIProperties props = new AIProperties(true, "gemini", List.of(), Map.of(), Map.of("gemini", elsewhere),
					new AIProperties.Limits(12000, 1500, 10, TIMEOUT, 1), new AIProperties.Cache(true, Duration.ofDays(7)),
					new AIProperties.TokenEstimate(4), new AIProperties.Source(true, 400), Duration.ofSeconds(30), Duration.ofSeconds(60), false, null,
					new AIProperties.Embedding("ollama", 16, 6000, 250));

			assertThat(new GeminiAIModelService(props, CLOCK).health().detail()).contains("not in ai.providers.gemini.allowed-hosts");
		}

	}

	@Nested
	class OpenRouter {

		final OpenRouterAIModelService service = new OpenRouterAIModelService(
				properties("openrouter", "vendor/model-x", "or-test-key", TIMEOUT, 1), CLOCK);

		@Test
		void sendsAnOpenAiStyleChatWithBearerAuthAndMapsReportedUsage() {
			API.stubFor(post("/v1/chat/completions").willReturn(json(200,
					"{\"id\":\"gen-1\",\"model\":\"vendor/model-x\",\"choices\":[{\"message\":{\"role\":\"assistant\","
							+ "\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":50,"
							+ "\"completion_tokens\":7}}")));

			AIResponse response = service.generate(request(null));

			assertThat(response.status()).isEqualTo(AIStatus.OK);
			assertThat(response.content()).isEqualTo("hello");
			assertThat(response.inputTokens()).isEqualTo(50);
			assertThat(response.outputTokens()).isEqualTo(7);
			API.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
				.withHeader("Authorization", equalTo("Bearer or-test-key"))
				.withRequestBody(equalToJson("{\"model\":\"vendor/model-x\",\"max_tokens\":300,\"stream\":false,"
						+ "\"messages\":[{\"role\":\"system\",\"content\":\"SYSTEM TEXT\"},"
						+ "{\"role\":\"user\",\"content\":\"USER TEXT\"}]}", true, true)));
		}

		@Test
		void requestsJsonSchemaOutputOnlyWhenTheModelListSaysItIsSupported() {
			API.stubFor(get("/v1/models").willReturn(json(200, "{\"data\":[{\"id\":\"vendor/model-x\",\"context_length\":65536,"
					+ "\"supported_parameters\":[\"max_tokens\",\"structured_outputs\"]}]}")));
			API.stubFor(post("/v1/chat/completions").willReturn(json(200,
					"{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")));

			service.generate(request(SCHEMA));
			assertThat(API.getAllServeEvents().get(0).getRequest().getBodyAsString()).doesNotContain("response_format");

			assertThat(service.health().status()).isEqualTo(ProviderHealth.Status.UP);
			assertThat(service.getCapabilities().maxContextTokens()).isEqualTo(65536);
			service.generate(request(SCHEMA));
			API.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
				.withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
				.withRequestBody(matchingJsonPath("$.response_format.json_schema.schema.required[0]", equalTo("summary"))));
		}

		@Test
		void insufficientCreditsAndRateLimitsAreRateLimited() {
			API.stubFor(post("/v1/chat/completions").willReturn(json(402, "{\"error\":{\"code\":402}}")));
			assertThat(service.generate(request(null)).status()).isEqualTo(AIStatus.RATE_LIMITED);

			API.stubFor(post("/v1/chat/completions").willReturn(json(429, "{}")
				.withHeader("X-RateLimit-Reset", String.valueOf(CLOCK.instant().plusSeconds(90).toEpochMilli()))));
			AIResponse limited = service.generate(request(null));
			assertThat(limited.status()).isEqualTo(AIStatus.RATE_LIMITED);
			assertThat(limited.retryAfter()).isEqualTo(CLOCK.instant().plusSeconds(90));
		}

		@Test
		void anErrorObjectInA200BodyIsAnError() {
			API.stubFor(post("/v1/chat/completions").willReturn(json(200, "{\"error\":{\"message\":\"upstream\"}}")));

			assertThat(service.generate(request(null)).status()).isEqualTo(AIStatus.ERROR);
		}

		@Test
		void aRejectedKeyIsAnError() {
			API.stubFor(post("/v1/chat/completions").willReturn(json(401, "{}")));

			AIResponse response = service.generate(request(null));

			assertThat(response.status()).isEqualTo(AIStatus.ERROR);
			assertThat(response.detail()).contains("rejected the API key").doesNotContain("or-test-key");
		}

	}

	@Nested
	class TimeoutAndRetryLimits {

		@Test
		void aServerErrorIsRetriedExactlyMaxRetriesTimes() {
			API.stubFor(post("/api/chat").willReturn(json(503, "{}")));
			OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "m", null, TIMEOUT, 1), CLOCK);

			AIResponse response = service.generate(request(null));

			assertThat(response.status()).isEqualTo(AIStatus.ERROR);
			API.verify(2, postRequestedFor(urlEqualTo("/api/chat")));
		}

		@Test
		void aTransientServerErrorRecoversOnTheRetry() {
			API.stubFor(post("/api/chat").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
				.willReturn(json(502, "{}")).willSetStateTo("recovered"));
			API.stubFor(post("/api/chat").inScenario("flaky").whenScenarioStateIs("recovered")
				.willReturn(json(200, "{\"message\":{\"content\":\"fine\"}}")));
			OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "m", null, TIMEOUT, 1), CLOCK);

			assertThat(service.generate(request(null)).content()).isEqualTo("fine");
			API.verify(2, postRequestedFor(urlEqualTo("/api/chat")));
		}

		@Test
		void zeroRetriesMeansOneAttempt() {
			API.stubFor(post("/api/chat").willReturn(json(500, "{}")));
			OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "m", null, TIMEOUT, 0), CLOCK);

			service.generate(request(null));

			API.verify(1, postRequestedFor(urlEqualTo("/api/chat")));
		}

		@Test
		void aTimeoutIsReportedAndNeverRetried() {
			API.stubFor(post("/api/chat").willReturn(json(200, "{\"message\":{\"content\":\"late\"}}").withFixedDelay(6000)));
			OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "m", null, 1, 1), CLOCK);

			long started = System.nanoTime();
			AIResponse response = service.generate(request(null));
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;

			assertThat(response.status()).isEqualTo(AIStatus.TIMEOUT);
			assertThat(elapsedMs).as("the 1 s timeout applied, not the 6 s delay").isLessThan(5000);
			API.verify(1, postRequestedFor(urlEqualTo("/api/chat")));
		}

		@Test
		void aRateLimitIsNeverRetriedAndMakesTheProviderTemporarilyUnavailable() {
			API.stubFor(post("/api/chat").willReturn(json(429, "{}").withHeader("Retry-After", "120")));
			OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "m", null, TIMEOUT, 1), CLOCK);
			ProviderAvailability availability = new ProviderAvailability(CLOCK, Duration.ofSeconds(30), Duration.ofSeconds(60));

			AIResponse response = service.generate(request(null));
			availability.markRateLimited(service.getProviderId(), response.retryAfter());

			assertThat(response.status()).isEqualTo(AIStatus.RATE_LIMITED);
			API.verify(1, postRequestedFor(urlEqualTo("/api/chat")));
			assertThat(availability.health(service).status()).isEqualTo(ProviderHealth.Status.RATE_LIMITED);
			assertThat(availability.rateLimitedUntil("ollama")).isEqualTo(CLOCK.instant().plusSeconds(120));
		}

		@Test
		void maxRetriesAboveThreeIsRejectedAtStartup() {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AIProperties.Limits(12000, 1500, 10, 45, 5))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

}
