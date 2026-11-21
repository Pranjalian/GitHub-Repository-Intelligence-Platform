package com.repointelligence.architecture.domain;

import java.util.Map;
import java.util.Objects;

/**
 * An annotation as written on a symbol: its name (simple or qualified, as in the source) and its
 * literal attribute values only (strings, numbers, booleans, enum constants, class literals). Anything
 * else is left out rather than evaluated. A single-member annotation's value is stored under {@code value}.
 */
public record AnnotationInfo(String name, Map<String, String> attributes) {

	public AnnotationInfo {
		Objects.requireNonNull(name, "name");
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
	}

	/** The simple name: {@code org.springframework.stereotype.Service} and {@code Service} both give {@code Service}. */
	public String simpleName() {
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1) : name;
	}

}
