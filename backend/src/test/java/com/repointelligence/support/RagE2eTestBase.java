package com.repointelligence.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared context for the RAG end-to-end tests (MVP 4): AI enabled, chat through a WireMock stand-in for
 * Ollama, and embeddings from the deterministic {@link HashingEmbedder} (selected as the embedding
 * provider), so indexing, retrieval and Q&A run end to end without any model. All RAG E2E classes extend
 * this, so they share one Spring context.
 */
@Import(RagE2eTestBase.RagTestConfig.class)
public abstract class RagE2eTestBase extends E2eTestBase {

	protected static final WireMockServer OLLAMA = new WireMockServer(options().dynamicPort());

	static {
		OLLAMA.start();
		Runtime.getRuntime().addShutdownHook(new Thread(OLLAMA::stop));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RagTestConfig {

		@Bean
		HashingEmbedder hashingEmbedder() {
			return new HashingEmbedder(64);
		}

	}

	@DynamicPropertySource
	static void ragProperties(DynamicPropertyRegistry registry) {
		registry.add("ai.enabled", () -> "true");
		registry.add("ai.default-provider", () -> "ollama");
		registry.add("ai.providers.ollama.enabled", () -> "true");
		registry.add("ai.providers.ollama.base-url", () -> "http://localhost:" + OLLAMA.port());
		registry.add("ai.providers.ollama.chat-model", () -> "test-model");
		registry.add("ai.providers.ollama.allowed-hosts", () -> "localhost");
		registry.add("ai.health-cache-ttl", () -> "PT0S");
		registry.add("ai.embedding.provider", () -> HashingEmbedder.ID);
		registry.add("ai.embedding.batch-size", () -> "8");
	}

	@Autowired
	protected HashingEmbedder embedder;

	@BeforeEach
	void resetRag() {
		embedder.reset();
		OLLAMA.resetAll();
		OLLAMA.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/tags")
			.willReturn(okJson("{\"models\":[{\"name\":\"test-model:latest\",\"digest\":\"aa\"}]}")));
	}

	@AfterEach
	void cleanRag() {
		jdbc.update("DELETE FROM ai_usage_record");
		jdbc.update("DELETE FROM ai_cache");
		// embeddings are not snapshot-scoped (keyed by content hash); remove them with the repositories
		jdbc.update("DELETE FROM chunk_embedding");
	}

	protected static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

}
