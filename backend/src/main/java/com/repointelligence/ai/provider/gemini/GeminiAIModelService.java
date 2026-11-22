package com.repointelligence.ai.provider.gemini;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini through the Generative Language REST API ({@code models/{model}:generateContent}).
 * Hosted: only used after the operator acknowledges its data handling. The API key is sent in the
 * {@code x-goog-api-key} header, never in a URL. Reported token usage ({@code usageMetadata}) is mapped
 * to REPORTED; quota errors (429) mark the provider rate-limited until the reset the API states.
 * Health and context size come from {@code GET models/{model}} at runtime.
 */
@Component
@ConditionalOnExpression("${ai.enabled:false} and ${ai.providers.gemini.enabled:false}")
public class GeminiAIModelService extends ProviderSupport {

	public static final String ID = "gemini";

	static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

	private static final Pattern RETRY_DELAY = Pattern.compile("^(\\d+)(?:\\.\\d+)?s$");

	private volatile Integer runtimeContextTokens;

	public GeminiAIModelService(AIProperties properties, Clock clock) {
		super(ID, properties, clock, DEFAULT_BASE_URL, List.of("generativelanguage.googleapis.com"), true);
	}

	@Override
	public DataHandling dataHandling() {
		return DataHandling.HOSTED;
	}

	@Override
	public AIProviderCapabilities getCapabilities() {
		Integer context = runtimeContextTokens != null ? runtimeContextTokens : settings.maxContextTokens();
		return new AIProviderCapabilities(true, structuredOutputEnabled(true), false, false, context,
				settings.chatModel(), runtimeContextTokens != null ? "RUNTIME" : "CONFIGURATION");
	}

	@Override
	public ProviderHealth health() {
		String missing = missingConfiguration(true);
		if (missing != null) {
			return ProviderHealth.notConfigured(missing, clock.instant());
		}
		try {
			ProviderHttp.Result result = http().send(authorized(HttpRequest
				.newBuilder(http().resolve("/v1beta/models/" + settings.chatModel()))
				.GET()));
			if (result.success()) {
				JsonNode json = parse(result.body());
				runtimeContextTokens = json == null ? null : integer(json.get("inputTokenLimit"));
				return ProviderHealth.up("Model " + settings.chatModel() + " is available.", clock.instant());
			}
			return ProviderHealth.down(describe(result.status(), settings.chatModel()), clock.instant());
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
					"Gemini is not configured: " + (missing != null ? missing : "invalid model name") + ".");
		}
		ObjectNode body = JSON.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
		ObjectNode user = body.putArray("contents").addObject();
		user.put("role", "user");
		user.putArray("parts").addObject().put("text", request.userPrompt());
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("temperature", request.temperature());
		generation.put("maxOutputTokens", request.maxOutputTokens());
		if (request.responseSchema() != null && structuredOutputEnabled(true)) {
			// JSON mode; the schema itself is in the prompt and validated by the gateway
			generation.put("responseMimeType", "application/json");
		}
		ProviderHttp.Result result;
		try {
			result = http().send(authorized(HttpRequest
				.newBuilder(http().resolve("/v1beta/models/" + model + ":generateContent"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))));
		}
		catch (ProviderHttp.CallFailure failure) {
			return fromFailure(failure, model);
		}
		if (result.status() == 429) {
			return AIResponse.rateLimited(ID, model, "Gemini quota or rate limit reached (HTTP 429).",
					retryAfter(result));
		}
		if (!result.success()) {
			AIStatus status = result.status() == 404 ? AIStatus.UNAVAILABLE : AIStatus.ERROR;
			return AIResponse.failure(ID, model, status, describe(result.status(), model));
		}
		JsonNode json = parse(result.body());
		JsonNode candidate = json == null ? null : json.path("candidates").path(0);
		StringBuilder content = new StringBuilder();
		if (candidate != null) {
			for (JsonNode part : candidate.path("content").path("parts")) {
				String text = text(part.get("text"));
				if (text != null) {
					content.append(text);
				}
			}
		}
		if (content.isEmpty()) {
			String reason = candidate == null ? null : text(candidate.get("finishReason"));
			String blocked = json == null ? null : text(json.path("promptFeedback").get("blockReason"));
			return AIResponse.failure(ID, model, AIStatus.ERROR, "Gemini returned no text"
					+ (reason != null ? " (finish reason " + reason + ")" : blocked != null ? " (blocked: " + blocked + ")" : "")
					+ ".");
		}
		JsonNode usage = json.path("usageMetadata");
		String answeredModel = json.hasNonNull("modelVersion") ? json.get("modelVersion").asString() : model;
		return AIResponse.ok(ID, answeredModel, content.toString(), integer(usage.get("promptTokenCount")),
				integer(usage.get("candidatesTokenCount")));
	}

	private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
		return builder.header("x-goog-api-key", settings.apiKey()).header("Accept", "application/json");
	}

	/** Retry-After header, else the RetryInfo delay in the error details (e.g. "17s"). */
	private Instant retryAfter(ProviderHttp.Result result) {
		Instant now = clock.instant();
		Instant header = result.retryAfter(now);
		if (header != null) {
			return header;
		}
		JsonNode json = parse(result.body());
		if (json == null) {
			return null;
		}
		for (JsonNode detail : json.path("error").path("details")) {
			String delay = text(detail.get("retryDelay"));
			if (delay != null) {
				Matcher matcher = RETRY_DELAY.matcher(delay.trim());
				if (matcher.matches()) {
					return now.plusSeconds(Long.parseLong(matcher.group(1)));
				}
			}
		}
		return null;
	}

	private static String describe(int status, String model) {
		return switch (status) {
			case 400, 401, 403 -> "Gemini rejected the request or the API key (HTTP " + status + ").";
			case 404 -> "Gemini does not know model " + model + " (HTTP 404).";
			case 429 -> "Gemini quota or rate limit reached (HTTP 429).";
			default -> "Gemini answered HTTP " + status + ".";
		};
	}

}
