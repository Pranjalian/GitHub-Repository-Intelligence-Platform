package com.repointelligence.analysis.spi;

import com.repointelligence.common.model.Confidence;
import java.util.Objects;

/**
 * One place where a test class names a production type or calls one of its methods. The type is
 * fully qualified (resolved from imports and the test's package); nothing unresolved is reported.
 *
 * @param testMethod the test method containing the reference, null for class-level references (fields)
 * @param member the called method's name, null for a type use
 * @param argumentCount the number of arguments of a call, null for a type use
 * @param kind {@code CALL}, {@code STATIC_CALL}, {@code INSTANTIATION} or {@code TYPE_USE}
 */
public record TestReferenceResult(String testMethod, String referencedType, String member, Integer argumentCount,
		String kind, int line, Confidence confidence) {

	public TestReferenceResult {
		Objects.requireNonNull(referencedType, "referencedType");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(confidence, "confidence");
	}

}
