package com.repointelligence.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing for provider adapters: settings checks, the HTTP helper and JSON reading. Adapters
 * keep the provider-specific request and response formats.
 */
public abstract class ProviderSupport implements AIModelService {

	/** Model names are only ever taken from configuration, but they still must not change a URL path. */
	private static final Pattern SAFE_MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,199}");

	protected static final JsonMapper JSON = JsonMapper.builder().build();

	protected final String id;

	protected final AIProperties properties;

	protected final AIProperties.ProviderSettings settings;

	protected final Clock clock;

	private final ProviderHttp http;

	private final String configurationError;

	protected ProviderSupport(String id, AIProperties properties, Clock clock, String defaultBaseUrl,
			java.util.List<String> defaultHosts, boolean requireHttps) {
		this.id = id;
		this.properties = properties;
		this.clock = clock;
		AIProperties.ProviderSettings configured = properties.provider(id);
		this.settings = configured != null ? configured
				: new AIProperties.ProviderSettings(false, null, null, null, false, null, null, null, null, null, null, null, null);
		String baseUrl = settings.baseUrl() == null || settings.baseUrl().isBlank() ? defaultBaseUrl : settings.baseUrl();
		java.util.List<String> hosts = settings.allowedHosts().isEmpty() ? defaultHosts : settings.allowedHosts();
		ProviderHttp built = null;
		String error = null;
		try {
			built = new ProviderHttp(id, ProviderHttp.checkedBaseUri(id, baseUrl, hosts,
					settings.requireHttps() != null ? settings.requireHttps() : requireHttps),
					properties.limits().timeout(), properties.limits().maxRetries(), Duration.ofMillis(500));
		}
		catch (IllegalStateException ex) {
			error = ex.getMessage();
		}
		this.http = built;
		this.configurationError = error;
	}

	@Override
	public String getProviderId() {
		return id;
	}

	/** Null when the adapter can be called; otherwise what is missing, without secret values. */
	protected String missingConfiguration(boolean needsKey) {
		if (configurationError != null) {
			return configurationError;
		}
		if (settings.chatModel() == null || settings.chatModel().isBlank()) {
			return "ai.providers." + id + ".chat-model is not set";
		}
		if (!safeModel(settings.chatModel())) {
			return "ai.providers." + id + ".chat-model contains unsupported characters";
		}
		if (needsKey && (settings.apiKey() == null || settings.apiKey().isBlank())) {
			return "the API key for " + id + " is not set";
		}
		return null;
	}

	protected ProviderHttp http() {
		return http;
	}

	/** The model of a request: the feature route's override, else the configured chat model. */
	protected String modelFor(AIRequest request) {
		String model = request.model() == null || request.model().isBlank() ? settings.chatModel() : request.model();
		return safeModel(model) ? model : null;
	}

	private static boolean safeModel(String model) {
		return model != null && SAFE_MODEL.matcher(model).matches() && !model.contains("..");
	}

	protected AIResponse fromFailure(ProviderHttp.CallFailure failure, String model) {
		return switch (failure.kind()) {
			case TIMEOUT -> AIResponse.failure(id, model, AIStatus.TIMEOUT, failure.getMessage() + ".");
			case UNREACHABLE -> AIResponse.failure(id, model, AIStatus.UNAVAILABLE, failure.getMessage() + ".");
			case IO, INTERRUPTED -> AIResponse.failure(id, model, AIStatus.ERROR, failure.getMessage() + ".");
		};
	}

	protected static JsonNode parse(String body) {
		try {
			return JSON.readTree(body == null ? "" : body);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	protected static Integer integer(JsonNode node) {
		return node != null && node.isNumber() ? node.asInt() : null;
	}

	protected static String text(JsonNode node) {
		return node != null && node.isString() ? node.asString() : null;
	}

	protected boolean structuredOutputEnabled(boolean runtimeSupport) {
		return settings.structuredOutput() != null ? settings.structuredOutput() : runtimeSupport;
	}

}
