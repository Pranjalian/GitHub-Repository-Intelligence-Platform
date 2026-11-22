package com.repointelligence.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the versioned templates from {@code classpath:ai/prompt/*.yml} at startup and serves the
 * highest version per feature. A malformed template fails startup: a broken prompt must never be sent.
 */
@Component
public class PromptTemplateRegistry {

	private static final Logger log = LoggerFactory.getLogger(PromptTemplateRegistry.class);

	static final String LOCATION = "classpath:ai/prompt/*.yml";

	private final Map<String, PromptTemplate> byFeature = new TreeMap<>();

	public PromptTemplateRegistry() {
		this(LOCATION);
	}

	PromptTemplateRegistry(String location) {
		try {
			for (Resource resource : new PathMatchingResourcePatternResolver().getResources(location)) {
				PromptTemplate template = load(resource);
				byFeature.merge(template.feature(), template, (a, b) -> a.version() >= b.version() ? a : b);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read prompt templates", ex);
		}
		log.info("Loaded prompt templates: {}", byFeature.values().stream().map(PromptTemplate::promptVersion).toList());
	}

	/** The current template of a feature; throws when the feature has none (a programming error). */
	public PromptTemplate forFeature(String feature) {
		PromptTemplate template = byFeature.get(feature);
		if (template == null) {
			throw new IllegalStateException("No prompt template for feature " + feature);
		}
		return template;
	}

	public Map<String, PromptTemplate> all() {
		return Map.copyOf(byFeature);
	}

	@SuppressWarnings("unchecked")
	private static PromptTemplate load(Resource resource) throws IOException {
		Map<String, Object> raw;
		try (InputStream in = resource.getInputStream()) {
			raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
		}
		String name = resource.getFilename();
		String id = required(raw, "id", name);
		int version = ((Number) raw.get("version")).intValue();
		if (name != null && !name.equals(id + ".v" + version + ".yml")) {
			throw new IllegalStateException("Prompt template " + name + " must be named " + id + ".v" + version + ".yml");
		}
		Object schema = raw.get("schema");
		return new PromptTemplate(id, version, required(raw, "feature", name), required(raw, "system", name),
				required(raw, "task", name), schema == null ? null : (Map<String, Object>) schema,
				((Number) raw.getOrDefault("max-output-tokens", 1000)).intValue(),
				((Number) raw.getOrDefault("temperature", 0.2)).doubleValue());
	}

	private static String required(Map<String, Object> raw, String key, String name) {
		Object value = raw.get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalStateException("Prompt template " + name + " has no " + key);
		}
		return text;
	}

}
