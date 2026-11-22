package com.repointelligence.ai.provider.ollama;

import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderCapabilities;
import com.repointelligence.ai.provider.AIRequest;
import com.repointelligence.ai.provider.AIResponse;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.DataHandling;
import com.repointelligence.ai.provider.EmbeddingHealth;
import com.repointelligence.ai.provider.EmbeddingModelService;
import com.repointelligence.ai.provider.EmbeddingPurpose;
import com.repointelligence.ai.provider.EmbeddingResponse;
import com.repointelligence.ai.provider.ProviderHealth;
import com.repointelligence.ai.provider.ProviderHttp;
import com.repointelligence.ai.provider.ProviderSupport;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local models through the Ollama REST API: {@code POST /api/chat} (non-streaming) and
 * {@code GET /api/tags} for health and the model list. Structured output uses the {@code format}
 * parameter with the JSON schema. Token counts come from {@code prompt_eval_count}/{@code eval_count}
 * when Ollama returns them. Embeddings (MVP 4) use {@code POST /api/embed} with the separately configured
 * {@code embedding-model}; its version is the model digest from {@code /api/tags}. Only created when
 * {@code ai.enabled} and {@code ai.providers.ollama.enabled}.
 */
@Component
@ConditionalOnExpression("${ai.enabled:false} and ${ai.providers.ollama.enabled:false}")
public class OllamaAIModelService extends ProviderSupport implements EmbeddingModelService {

	public static final String ID = "ollama";

	static final String DEFAULT_BASE_URL = "http://localhost:11434";

	static final List<String> DEFAULT_HOSTS = List.of("localhost", "127.0.0.1", "host.docker.internal", "ollama");

	public OllamaAIModelService(AIProperties properties, Clock clock) {
		super(ID, properties, clock, DEFAULT_BASE_URL, DEFAULT_HOSTS, false);
	}

	@Override
	public DataHandling dataHandling() {
		return DataHandling.LOCAL;
	}

	@Override
	public AIProviderCapabilities getCapabilities() {
		return AIProviderCapabilities.fromConfiguration(settings.chatModel(), settings.maxContextTokens(),
				structuredOutputEnabled(true));
	}

	@Override
	public ProviderHealth health() {
		String missing = missingConfiguration(false);
		if (missing != null) {
			return ProviderHealth.notConfigured(missing, clock.instant());
		}
		try {
			ProviderHttp.Result result = http().send(HttpRequest.newBuilder(http().resolve("/api/tags"))
				.header("Accept", "application/json")
				.GET());
			if (!result.success()) {
				return ProviderHealth.down("Ollama answered HTTP " + result.status() + " to the model list.", clock.instant());
			}
			JsonNode models = parse(result.body()) == null ? null : parse(result.body()).get("models");
			if (models == null || !models.isArray()) {
				return ProviderHealth.down("Ollama returned an unexpected model list.", clock.instant());
			}
			String wanted = settings.chatModel();
			for (JsonNode model : models) {
				String name = text(model.get("name"));
				if (name != null && (name.equals(wanted) || name.equals(wanted + ":latest"))) {
					return ProviderHealth.up("Model " + wanted + " is available.", clock.instant());
				}
			}
			return ProviderHealth.down("Model " + wanted + " is not pulled in Ollama (ollama pull " + wanted + ").",
					clock.instant());
		}
		catch (ProviderHttp.CallFailure failure) {
			return ProviderHealth.down(failure.getMessage() + ".", clock.instant());
		}
	}

	@Override
	public AIResponse generate(AIRequest request) {
		String model = modelFor(request);
		String missing = missingConfiguration(false);
		if (missing != null || model == null) {
			return AIResponse.failure(ID, model, AIStatus.UNAVAILABLE,
					"Ollama is not configured: " + (missing != null ? missing : "invalid model name") + ".");
		}
		ObjectNode body = JSON.createObjectNode();
		body.put("model", model);
		body.put("stream", false);
		ArrayNode messages = body.putArray("messages");
		messages.addObject().put("role", "system").put("content", request.systemPrompt());
		messages.addObject().put("role", "user").put("content", request.userPrompt());
		ObjectNode options = body.putObject("options");
		options.put("temperature", request.temperature());
		options.put("num_predict", request.maxOutputTokens());
		if (request.responseSchema() != null && structuredOutputEnabled(true)) {
			body.set("format", JSON.valueToTree(request.responseSchema()));
		}
		ProviderHttp.Result result;
		try {
			result = http().send(HttpRequest.newBuilder(http().resolve("/api/chat"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)));
		}
		catch (ProviderHttp.CallFailure failure) {
			return fromFailure(failure, model);
		}
		if (result.status() == 404) {
			return AIResponse.failure(ID, model, AIStatus.UNAVAILABLE, "Ollama does not have model " + model + ".");
		}
		if (result.status() == 429) {
			return AIResponse.rateLimited(ID, model, "Ollama is busy (HTTP 429).", result.retryAfter(clock.instant()));
		}
		if (!result.success()) {
			return AIResponse.failure(ID, model, AIStatus.ERROR, "Ollama answered HTTP " + result.status() + ".");
		}
		JsonNode json = parse(result.body());
		String content = json == null ? null : text(json.path("message").get("content"));
		if (content == null) {
			return AIResponse.failure(ID, model, AIStatus.ERROR, "Ollama returned no message content.");
		}
		String answeredModel = json.hasNonNull("model") ? json.get("model").asString() : model;
		return AIResponse.ok(ID, answeredModel, content, integer(json.get("prompt_eval_count")),
				integer(json.get("eval_count")));
	}

	// ── embeddings ───────────────────────────────────────────────────────────────────────────────

	@Override
	public EmbeddingHealth embeddingHealth() {
		String wanted = settings.embeddingModel();
		String missing = missingEmbeddingConfiguration(false);
		if (missing != null) {
			return new EmbeddingHealth(ProviderHealth.notConfigured(missing, clock.instant()), wanted, null);
		}
		try {
			ProviderHttp.Result result = http().send(HttpRequest.newBuilder(http().resolve("/api/tags"))
				.header("Accept", "application/json")
				.GET());
			if (!result.success()) {
				return new EmbeddingHealth(
						ProviderHealth.down("Ollama answered HTTP " + result.status() + " to the model list.", clock.instant()),
						wanted, null);
			}
			JsonNode json = parse(result.body());
			JsonNode models = json == null ? null : json.get("models");
			if (models == null || !models.isArray()) {
				return new EmbeddingHealth(ProviderHealth.down("Ollama returned an unexpected model list.", clock.instant()),
						wanted, null);
			}
			for (JsonNode model : models) {
				String name = text(model.get("name"));
				if (name != null && (name.equals(wanted) || name.equals(wanted + ":latest"))) {
					String digest = text(model.get("digest"));
					// the digest identifies the exact weights; a re-pull under the same name gets new embeddings
					String version = digest == null || digest.isBlank() ? "unversioned"
							: "digest:" + digest.replace("sha256:", "").substring(0, Math.min(12, digest.replace("sha256:", "").length()));
					return new EmbeddingHealth(ProviderHealth.up("Embedding model " + wanted + " is available.", clock.instant()),
							wanted, version);
				}
			}
			return new EmbeddingHealth(ProviderHealth.down(
					"Embedding model " + wanted + " is not pulled in Ollama (ollama pull " + wanted + ").", clock.instant()),
					wanted, null);
		}
		catch (ProviderHttp.CallFailure failure) {
			return new EmbeddingHealth(ProviderHealth.down(failure.getMessage() + ".", clock.instant()), wanted, null);
		}
	}

	@Override
	public EmbeddingResponse embed(List<String> inputs, EmbeddingPurpose purpose) {
		String model = settings.embeddingModel();
		String missing = missingEmbeddingConfiguration(false);
		if (missing != null) {
			return EmbeddingResponse.failure(AIStatus.UNAVAILABLE, model, "Ollama embeddings are not configured: " + missing + ".");
		}
		if (inputs.isEmpty()) {
			return EmbeddingResponse.ok(model, List.of(), null);
		}
		ObjectNode body = JSON.createObjectNode();
		body.put("model", model);
		ArrayNode input = body.putArray("input");
		inputs.forEach(input::add);
		// over-long inputs are cut to the model's context by Ollama instead of failing the batch
		body.put("truncate", true);
		ProviderHttp.Result result;
		try {
			result = http().send(HttpRequest.newBuilder(http().resolve("/api/embed"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)));
		}
		catch (ProviderHttp.CallFailure failure) {
			AIResponse mapped = fromFailure(failure, model);
			return EmbeddingResponse.failure(mapped.status(), model, mapped.detail());
		}
		if (result.status() == 404) {
			return EmbeddingResponse.failure(AIStatus.UNAVAILABLE, model, "Ollama does not have embedding model " + model + ".");
		}
		if (result.status() == 429) {
			return EmbeddingResponse.rateLimited(model, "Ollama is busy (HTTP 429).", result.retryAfter(clock.instant()));
		}
		if (!result.success()) {
			return EmbeddingResponse.failure(AIStatus.ERROR, model, "Ollama answered HTTP " + result.status()
					+ " to the embedding request (is " + model + " an embedding model?).");
		}
		JsonNode json = parse(result.body());
		JsonNode embeddings = json == null ? null : json.get("embeddings");
		if (embeddings == null || !embeddings.isArray() || embeddings.size() != inputs.size()) {
			return EmbeddingResponse.failure(AIStatus.INVALID_OUTPUT, model,
					"Ollama returned " + (embeddings == null || !embeddings.isArray() ? "no" : embeddings.size())
							+ " embeddings for " + inputs.size() + " inputs.");
		}
		List<float[]> vectors = new ArrayList<>();
		for (JsonNode embedding : embeddings) {
			float[] vector = vector(embedding);
			if (vector == null || (!vectors.isEmpty() && vector.length != vectors.get(0).length)) {
				return EmbeddingResponse.failure(AIStatus.INVALID_OUTPUT, model,
						"Ollama returned an empty, non-numeric or inconsistent embedding.");
			}
			vectors.add(vector);
		}
		return EmbeddingResponse.ok(model, vectors, integer(json.get("prompt_eval_count")));
	}

}
