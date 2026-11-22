package com.repointelligence.ai.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.repointelligence.ai.provider.gemini.GeminiAIModelService;
import com.repointelligence.ai.provider.ollama.OllamaAIModelService;
import com.repointelligence.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Embedding adapters (MVP 4) against WireMock stand-ins, and the embedding router's rules: one provider, no
 * fallback, hosted only with acknowledgement and permission, model version from the provider.
 */
class EmbeddingAdapterTests {

	static final WireMockServer API = new WireMockServer(options().dynamicPort());

	static final Clock CLOCK = new MutableClock(Instant.parse("2026-11-22T10:00:00Z"));

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

	static AIProperties properties(String id, String embeddingModel, String key, boolean acknowledged) {
		AIProperties.ProviderSettings settings = new AIProperties.ProviderSettings(true, "http://localhost:" + API.port(),
				key, "chat-model", acknowledged, List.of("localhost"), null, null, null, null, null, false, null,
				embeddingModel);
		return new AIProperties(true, id, List.of(), Map.of(), Map.of(id, settings), new AIProperties.Limits(12000, 1500, 10, 10, 1),
				new AIProperties.Cache(true, Duration.ofDays(7)), new AIProperties.TokenEstimate(4),
				new AIProperties.Source(true, 400), Duration.ofSeconds(30), Duration.ofSeconds(60), false, null,
				new AIProperties.Embedding(id, 16, 6000, 250));
	}

	static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
	}

	@Nested
	class Ollama {

		final OllamaAIModelService service = new OllamaAIModelService(properties("ollama", "nomic-embed-text", null, false),
				CLOCK);

		@Test
		void embedsABatchWithTheEmbeddingModelNotTheChatModel() {
			API.stubFor(post("/api/embed").willReturn(json(200,
					"{\"model\":\"nomic-embed-text\",\"embeddings\":[[0.1,0.2,0.3],[0.4,0.5,0.6]],\"prompt_eval_count\":12}")));

			EmbeddingResponse response = service.embed(List.of("first", "second"), EmbeddingPurpose.DOCUMENT);

			assertThat(response.status()).isEqualTo(AIStatus.OK);
			assertThat(response.vectors()).hasSize(2);
			assertThat(response.vectors().get(1)).containsExactly(0.4f, 0.5f, 0.6f);
			assertThat(response.inputTokens()).isEqualTo(12);
			API.verify(postRequestedFor(urlEqualTo("/api/embed"))
				.withRequestBody(matchingJsonPath("$.model", equalTo("nomic-embed-text")))
				.withRequestBody(matchingJsonPath("$.input[1]", equalTo("second")))
				.withRequestBody(matchingJsonPath("$.truncate", equalTo("true"))));
		}

		@Test
		void theModelVersionIsTheDigestFromTheModelList() {
			API.stubFor(get("/api/tags").willReturn(json(200,
					"{\"models\":[{\"name\":\"nomic-embed-text:latest\",\"digest\":\"0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f\"}]}")));

			EmbeddingHealth health = service.embeddingHealth();

			assertThat(health.isUp()).isTrue();
			assertThat(health.modelVersion()).isEqualTo("digest:0a109f422b47");
		}

		@Test
		void aModelThatIsNotPulledIsDown() {
			API.stubFor(get("/api/tags").willReturn(json(200, "{\"models\":[{\"name\":\"llama3.2:latest\",\"digest\":\"ab\"}]}")));

			EmbeddingHealth health = service.embeddingHealth();

			assertThat(health.isUp()).isFalse();
			assertThat(health.health().detail()).contains("ollama pull nomic-embed-text");
		}

		@Test
		void aWrongNumberOfVectorsOrInconsistentDimensionsIsInvalid() {
			API.stubFor(post("/api/embed").willReturn(json(200, "{\"embeddings\":[[0.1,0.2],[0.3]]}")));

			assertThat(service.embed(List.of("a", "b"), EmbeddingPurpose.DOCUMENT).status()).isEqualTo(AIStatus.INVALID_OUTPUT);

			API.stubFor(post("/api/embed").willReturn(json(200, "{\"embeddings\":[[0.1,0.2]]}")));
			assertThat(service.embed(List.of("a", "b"), EmbeddingPurpose.DOCUMENT).status()).isEqualTo(AIStatus.INVALID_OUTPUT);
		}

		@Test
		void withoutAnEmbeddingModelNothingIsSent() {
			OllamaAIModelService unconfigured = new OllamaAIModelService(properties("ollama", " ", null, false), CLOCK);

			assertThat(unconfigured.embeddingModel()).isNull();
			assertThat(unconfigured.embeddingHealth().health().status()).isEqualTo(ProviderHealth.Status.NOT_CONFIGURED);
			assertThat(unconfigured.embed(List.of("x"), EmbeddingPurpose.QUERY).status()).isEqualTo(AIStatus.UNAVAILABLE);
			assertThat(API.getAllServeEvents()).isEmpty();
		}

		@Test
		void aRateLimitIsReportedWithoutRetrying() {
			API.stubFor(post("/api/embed").willReturn(json(429, "{}").withHeader("Retry-After", "30")));

			EmbeddingResponse response = service.embed(List.of("x"), EmbeddingPurpose.DOCUMENT);

			assertThat(response.status()).isEqualTo(AIStatus.RATE_LIMITED);
			assertThat(response.retryAfter()).isEqualTo(Instant.parse("2026-11-22T10:00:30Z"));
			API.verify(1, postRequestedFor(urlEqualTo("/api/embed")));
		}

	}

	@Nested
	class Gemini {

		final GeminiAIModelService service = new GeminiAIModelService(
				properties("gemini", "gemini-embedding-001", "gm-test-key", true), CLOCK);

		@Test
		void batchEmbedsWithRetrievalTaskTypesAndTheKeyInAHeader() {
			API.stubFor(post("/v1beta/models/gemini-embedding-001:batchEmbedContents").willReturn(json(200,
					"{\"embeddings\":[{\"values\":[1.0,0.0]},{\"values\":[0.0,1.0]}]}")));

			EmbeddingResponse documents = service.embed(List.of("a", "b"), EmbeddingPurpose.DOCUMENT);
			service.embed(List.of("q", "r"), EmbeddingPurpose.QUERY);

			assertThat(documents.isOk()).isTrue();
			assertThat(documents.vectors().get(0)).containsExactly(1.0f, 0.0f);
			API.verify(postRequestedFor(urlEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents"))
				.withHeader("x-goog-api-key", equalTo("gm-test-key"))
				.withRequestBody(matchingJsonPath("$.requests[0].model", equalTo("models/gemini-embedding-001")))
				.withRequestBody(matchingJsonPath("$.requests[1].content.parts[0].text", equalTo("b")))
				.withRequestBody(matchingJsonPath("$.requests[0].taskType", equalTo("RETRIEVAL_DOCUMENT"))));
			API.verify(postRequestedFor(urlEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents"))
				.withRequestBody(matchingJsonPath("$.requests[0].taskType", equalTo("RETRIEVAL_QUERY"))));
		}

		@Test
		void healthReadsTheModelVersionAndRejectsANonEmbeddingModel() {
			API.stubFor(get("/v1beta/models/gemini-embedding-001").willReturn(json(200,
					"{\"name\":\"models/gemini-embedding-001\",\"version\":\"001\",\"supportedGenerationMethods\":[\"embedContent\"]}")));
			assertThat(service.embeddingHealth().modelVersion()).isEqualTo("001");

			API.stubFor(get("/v1beta/models/gemini-embedding-001").willReturn(json(200,
					"{\"version\":\"001\",\"supportedGenerationMethods\":[\"generateContent\"]}")));
			EmbeddingHealth chatOnly = service.embeddingHealth();
			assertThat(chatOnly.isUp()).isFalse();
			assertThat(chatOnly.health().detail()).contains("does not support embeddings");
		}

	}

	@Nested
	class Router {

		@Test
		void aHostedEmbeddingProviderNeedsAcknowledgementAndARepositoryThatPermitsIt() {
			AIProperties unacknowledged = properties("gemini", "gemini-embedding-001", "k", false);
			GeminiAIModelService gemini = new GeminiAIModelService(unacknowledged, CLOCK);
			EmbeddingRouter router = new EmbeddingRouter(unacknowledged, List.of(gemini),
					new ProviderAvailability(CLOCK, Duration.ZERO, Duration.ofSeconds(60)), CLOCK);

			EmbeddingRouter.Selection selection = router.select(true);

			assertThat(selection.available()).isFalse();
			assertThat(selection.notConfigured()).isTrue();
			assertThat(selection.reason()).contains("data-handling-acknowledged=false");
			assertThat(API.getAllServeEvents()).isEmpty();

			AIProperties acknowledged = properties("gemini", "gemini-embedding-001", "k", true);
			EmbeddingRouter permitted = new EmbeddingRouter(acknowledged,
					List.of(new GeminiAIModelService(acknowledged, CLOCK)),
					new ProviderAvailability(CLOCK, Duration.ZERO, Duration.ofSeconds(60)), CLOCK);
			assertThat(permitted.select(false).reason()).contains("does not permit hosted processing");
		}

		@Test
		void withAiDisabledOrNoModelThereIsNoSelectionAndNoCall() {
			AIProperties props = properties("ollama", null, null, false);
			Map<String, AIProperties.ProviderSettings> providers = new HashMap<>(props.providers());
			AIProperties disabled = new AIProperties(false, "ollama", List.of(), Map.of(), providers, props.limits(),
					props.cache(), props.tokenEstimate(), props.source(), props.healthCacheTtl(), props.rateLimitCooldown(),
					false, null, props.embedding());
			EmbeddingRouter router = new EmbeddingRouter(disabled, List.of(new OllamaAIModelService(disabled, CLOCK)),
					new ProviderAvailability(CLOCK, Duration.ZERO, Duration.ofSeconds(60)), CLOCK);
			assertThat(router.select(true).reason()).contains("AI is disabled");

			EmbeddingRouter noModel = new EmbeddingRouter(props, List.of(new OllamaAIModelService(props, CLOCK)),
					new ProviderAvailability(CLOCK, Duration.ZERO, Duration.ofSeconds(60)), CLOCK);
			assertThat(noModel.select(true).reason()).contains("ai.providers.ollama.embedding-model");
			assertThat(API.getAllServeEvents()).isEmpty();
		}

		@Test
		void thereIsNoFallbackToAnotherEmbeddingProvider() {
			AIProperties props = properties("ollama", "nomic-embed-text", null, false);
			API.stubFor(get("/api/tags").willReturn(json(200, "{\"models\":[]}")));
			EmbeddingRouter router = new EmbeddingRouter(props, List.of(new OllamaAIModelService(props, CLOCK)),
					new ProviderAvailability(CLOCK, Duration.ZERO, Duration.ofSeconds(60)), CLOCK);

			EmbeddingRouter.Selection selection = router.select(true);

			assertThat(selection.available()).isFalse();
			assertThat(selection.notConfigured()).isFalse();
			assertThat(selection.reason()).contains("not pulled");
		}

	}

}
