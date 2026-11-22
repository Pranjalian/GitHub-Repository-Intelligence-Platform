package com.repointelligence.ai.provider.ollama;

import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderCapabilities;
import com.repointelligence.ai.provider.AIRequest;
import com.repointelligence.ai.provider.AIResponse;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.DataHandling;
import com.repointelligence.ai.provider.ProviderHealth;
import com.repointelligence.ai.provider.ProviderHttp;
import com.repointelligence.ai.provider.ProviderSupport;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
 * when Ollama returns them. Only created when {@code ai.enabled} and {@code ai.providers.ollama.enabled}.
 */
@Component
@ConditionalOnExpression("${ai.enabled:false} and ${ai.providers.ollama.enabled:false}")
public class OllamaAIModelService extends ProviderSupport {

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

}
