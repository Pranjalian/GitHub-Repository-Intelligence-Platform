package com.repointelligence.ai.application;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a model answer against the template's JSON schema (draft 2020-12, networknt). An answer that
 * is not a single JSON object (a code fence around it is tolerated) or breaks the schema is rejected:
 * the caller reports INVALID_OUTPUT and never shows it.
 */
public final class StructuredOutputValidator {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

	private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

	/** The parsed answer, or the reason it was rejected. */
	public record Validation(JsonNode value, String error) {

		public boolean valid() {
			return error == null;
		}

	}

	public Validation validate(String content, Map<String, Object> schema) {
		JsonNode node;
		try {
			node = JSON.readTree(stripFence(content));
		}
		catch (RuntimeException ex) {
			return new Validation(null, "the answer is not valid JSON");
		}
		if (node == null || !node.isObject()) {
			return new Validation(null, "the answer is not a JSON object");
		}
		Schema compiled = schemas.computeIfAbsent(JSON.writeValueAsString(schema), REGISTRY::getSchema);
		List<Error> errors = compiled.validate(node);
		if (!errors.isEmpty()) {
			String first = errors.get(0).getMessage();
			return new Validation(null, "the answer does not match the schema (" + errors.size() + " problem(s), e.g. "
					+ (first.length() > 160 ? first.substring(0, 160) : first) + ")");
		}
		return new Validation(node, null);
	}

	/** Accepts {@code ```json ... ```} around an otherwise bare JSON answer; nothing else. */
	static String stripFence(String content) {
		if (content == null) {
			return "";
		}
		String trimmed = content.strip();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			int lastFence = trimmed.lastIndexOf("```");
			if (firstNewline > 0 && lastFence > firstNewline) {
				return trimmed.substring(firstNewline + 1, lastFence).strip();
			}
		}
		return trimmed;
	}

}
