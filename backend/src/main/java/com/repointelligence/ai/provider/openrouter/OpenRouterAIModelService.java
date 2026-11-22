package com.repointelligence.ai.provider.openrouter;

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
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenRouter through its OpenAI-compatible chat completions API ({@code POST /v1/chat/completions}).
 * Hosted: only used after the operator acknowledges its data handling. The model is configurable;
 * nothing assumes that a model is free. Context size and structured-output support are read from
 * {@code GET /v1/models} at runtime; JSON-schema output is only requested when the model supports it.
 * Reported usage maps to REPORTED; 429 and 402 (quota or credits) mark the provider rate-limited.
 */
@Component
@ConditionalOnExpression("${ai.enabled:false} and ${ai.providers.openrouter.enabled:false}")
public class OpenRouterAIModelService extends ProviderSupport {

	public static final String ID = "openrouter";

	static final String DEFAULT_BASE_URL = "https://openrouter.ai/api";

	private volatile Integer runtimeContextTokens;

	private volatile Boolean runtimeStructuredOutput;

	public OpenRouterAIModelService(AIProperties properties, Clock clock) {
		super(ID, properties, clock, DEFAULT_BASE_URL, List.of("openrouter.ai"), true);
	}

	@Override
	public DataHandling dataHandling() {
		return DataHandling.HOSTED;
	}

	@Override
	public AIProviderCapabilities getCapabilities() {
		boolean runtimeKnown = runtimeContextTokens != null || runtimeStructuredOutput != null;
		Integer context = runtimeContextTokens != null ? runtimeContextTokens : settings.maxContextTokens();
		return new AIProviderCapabilities(true, structuredOutputEnabled(Boolean.TRUE.equals(runtimeStructuredOutput)), false,
				false, context, settings.chatModel(), runtimeKnown ? "RUNTIME" : "CONFIGURATION");
	}

	@Override
	public ProviderHealth health() {
		String missing = missingConfiguration(true);
		if (missing != null) {
			return ProviderHealth.notConfigured(missing, clock.instant());
		}
		try {
			ProviderHttp.Result result = http().send(authorized(HttpRequest.newBuilder(http().resolve("/v1/models")).GET()));
			if (!result.success()) {
				return ProviderHealth.down("OpenRouter answered HTTP " + result.status() + " to the model list.",
						clock.instant());
			}
			JsonNode json = parse(result.body());
			for (JsonNode model : json == null ? JSON.createArrayNode() : json.path("data")) {
				if (settings.chatModel().equals(text(model.get("id")))) {
					runtimeContextTokens = integer(model.get("context_length"));
					boolean structured = false;
					for (JsonNode parameter : model.path("supported_parameters")) {
						String name = text(parameter);
						structured |= "structured_outputs".equals(name) || "response_format".equals(name);
					}
					runtimeStructuredOutput = structured;
					return ProviderHealth.up("Model " + settings.chatModel() + " is listed.", clock.instant());
				}
			}
			return ProviderHealth.down("Model " + settings.chatModel() + " is not listed by OpenRouter.", clock.instant());
		}
		catch (ProviderHttp.CallFailure failure) {
			return ProviderHealth.down(failure.getMessage() + ".", clock.instant());
		}
	}

	@Override
	public AIResponse generate(AIRequest request) {
		String model = modelFor(request);
		String missing = missingConfiguration(true);
		if (missing != null || model == null) {
			return AIResponse.failure(ID, model, AIStatus.UNAVAILABLE,
					"OpenRouter is not configured: " + (missing != null ? missing : "invalid model name") + ".");
		}
		ObjectNode body = JSON.createObjectNode();
		body.put("model", model);
		ArrayNode messages = body.putArray("messages");
		messages.addObject().put("role", "system").put("content", request.systemPrompt());
		messages.addObject().put("role", "user").put("content", request.userPrompt());
		body.put("max_tokens", request.maxOutputTokens());
		body.put("temperature", request.temperature());
		body.put("stream", false);
		if (request.responseSchema() != null && structuredOutputEnabled(Boolean.TRUE.equals(runtimeStructuredOutput))) {
			ObjectNode format = body.putObject("response_format");
			format.put("type", "json_schema");
			ObjectNode schema = format.putObject("json_schema");
			schema.put("name", "insight");
			schema.put("strict", true);
			schema.set("schema", JSON.valueToTree(request.responseSchema()));
		}
		ProviderHttp.Result result;
		try {
			result = http().send(authorized(HttpRequest.newBuilder(http().resolve("/v1/chat/completions"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))));
		}
		catch (ProviderHttp.CallFailure failure) {
			return fromFailure(failure, model);
		}
		if (result.status() == 429 || result.status() == 402) {
			return AIResponse.rateLimited(ID, model, result.status() == 402
					? "OpenRouter reports insufficient credits or quota (HTTP 402)."
					: "OpenRouter rate limit reached (HTTP 429).", retryAfter(result));
		}
		if (!result.success()) {
			AIStatus status = result.status() == 404 ? AIStatus.UNAVAILABLE : AIStatus.ERROR;
			return AIResponse.failure(ID, model, status, switch (result.status()) {
				case 401, 403 -> "OpenRouter rejected the API key (HTTP " + result.status() + ").";
				case 404 -> "OpenRouter does not know model " + model + " (HTTP 404).";
				default -> "OpenRouter answered HTTP " + result.status() + ".";
			});
		}
		JsonNode json = parse(result.body());
		if (json == null) {
			return AIResponse.failure(ID, model, AIStatus.ERROR, "OpenRouter returned an unreadable response.");
		}
		if (json.has("error")) {
			return AIResponse.failure(ID, model, AIStatus.ERROR, "OpenRouter returned an error in the response body.");
		}
		String content = text(json.path("choices").path(0).path("message").get("content"));
		if (content == null || content.isEmpty()) {
			return AIResponse.failure(ID, model, AIStatus.ERROR, "OpenRouter returned no message content.");
		}
		JsonNode usage = json.path("usage");
		String answeredModel = json.hasNonNull("model") ? json.get("model").asString() : model;
		return AIResponse.ok(ID, answeredModel, content, integer(usage.get("prompt_tokens")),
				integer(usage.get("completion_tokens")));
	}

	private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
		return builder.header("Authorization", "Bearer " + settings.apiKey()).header("Accept", "application/json");
	}

	/** Retry-After, else X-RateLimit-Reset (epoch milliseconds). */
	private Instant retryAfter(ProviderHttp.Result result) {
		Instant header = result.retryAfter(clock.instant());
		if (header != null) {
			return header;
		}
		return result.headers().firstValue("X-RateLimit-Reset").map(value -> {
			try {
				return Instant.ofEpochMilli(Long.parseLong(value.trim()));
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}).orElse(null);
	}

}
