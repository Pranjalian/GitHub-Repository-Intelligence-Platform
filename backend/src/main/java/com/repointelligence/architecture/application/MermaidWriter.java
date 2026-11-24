package com.repointelligence.architecture.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds Mermaid source from untrusted, repository-derived names. Node identifiers are synthetic
 * ({@code n1}, {@code n2}, ...) and never contain repository text; labels are always quoted and every
 * character that could end the quote, start markup or an entity is replaced by a Mermaid entity code,
 * so a symbol name can neither break the diagram syntax nor inject HTML or click handlers. The frontend
 * additionally renders with {@code securityLevel: 'strict'}.
 */
public final class MermaidWriter {

	static final int LABEL_MAX = 80;

	private final StringBuilder out = new StringBuilder();

	private final Map<String, String> ids = new LinkedHashMap<>();

	private final String prefix;

	public MermaidWriter(String header, String prefix) {
		this.prefix = prefix;
		out.append(header).append('\n');
	}

	/** The synthetic id of a node, declaring it with its label on first use. */
	public String node(String key, String label, String indent) {
		String existing = ids.get(key);
		if (existing != null) {
			return existing;
		}
		String id = prefix + (ids.size() + 1);
		ids.put(key, id);
		out.append(indent).append(id).append("[\"").append(label(label)).append("\"]\n");
		return id;
	}

	public boolean has(String key) {
		return ids.containsKey(key);
	}

	public String id(String key) {
		return ids.get(key);
	}

	public void line(String text) {
		out.append(text).append('\n');
	}

	public void edge(String fromId, String toId, String label) {
		if (label == null || label.isBlank()) {
			out.append("  ").append(fromId).append(" --> ").append(toId).append('\n');
		}
		else {
			out.append("  ").append(fromId).append(" -->|\"").append(label(label)).append("\"| ").append(toId).append('\n');
		}
	}

	@Override
	public String toString() {
		return out.toString();
	}

	/**
	 * A label safe inside {@code "..."}: control characters become spaces, the text is capped, and
	 * {@code # " < > & ` | [ ] { } ( ) ;} become numeric entity codes ({@code #35;} etc.).
	 */
	public static String label(String text) {
		if (text == null) {
			return "";
		}
		String flat = text.replaceAll("[\\p{Cntrl}]", " ").strip();
		if (flat.length() > LABEL_MAX) {
			flat = flat.substring(0, LABEL_MAX - 1) + "…";
		}
		StringBuilder safe = new StringBuilder(flat.length());
		for (int i = 0; i < flat.length(); i++) {
			char c = flat.charAt(i);
			switch (c) {
				case '#', '"', '<', '>', '&', '`', '|', '[', ']', '{', '}', '(', ')', ';', '\\' ->
					safe.append('#').append((int) c).append(';');
				default -> safe.append(c);
			}
		}
		return safe.toString();
	}

	/** An identifier for {@code erDiagram} entity names, which cannot be quoted: letters, digits and underscores only. */
	static String identifier(String text, String fallback) {
		String cleaned = text == null ? "" : text.replaceAll("[^A-Za-z0-9_]", "_");
		if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) {
			cleaned = fallback + "_" + cleaned;
		}
		return cleaned.length() > LABEL_MAX ? cleaned.substring(0, LABEL_MAX) : cleaned;
	}

}
