package com.repointelligence.ai.provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The providers this build knows, with their data handling, so the settings page can list a provider
 * (and say it is local or hosted) even when its bean does not exist because it is disabled.
 */
public final class ProviderCatalog {

	private static final Map<String, DataHandling> KNOWN = new LinkedHashMap<>();

	static {
		KNOWN.put("ollama", DataHandling.LOCAL);
		KNOWN.put("gemini", DataHandling.HOSTED);
		KNOWN.put("openrouter", DataHandling.HOSTED);
	}

	private ProviderCatalog() {
	}

	public static Map<String, DataHandling> known() {
		return Map.copyOf(KNOWN);
	}

	public static Iterable<String> ids() {
		return KNOWN.keySet();
	}

	public static DataHandling dataHandling(String id) {
		return KNOWN.get(id);
	}

}
