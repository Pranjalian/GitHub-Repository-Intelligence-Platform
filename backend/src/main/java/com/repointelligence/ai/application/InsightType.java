package com.repointelligence.ai.application;

/** The AI insight features; each has one prompt template ({@code ai/prompt/<feature>.v<n>.yml}). */
public enum InsightType {

	/** targetId: id of a type symbol (class, interface, enum, record, annotation). */
	EXPLAIN_CLASS("explain-class"),
	/** targetId: id of a method or constructor symbol. */
	EXPLAIN_METHOD("explain-method"),
	/** targetId: id of a finding. */
	EXPLAIN_FINDING("explain-finding"),
	/** targetId: id of a type, method or constructor symbol. */
	SUGGEST_REFACTORING("suggest-refactoring"),
	/** targetId: module path as in the report's structure section (e.g. {@code .} or {@code core}). */
	SUMMARIZE_MODULE("summarize-module"),
	/** No target: the whole snapshot's deduplicated findings. */
	TECH_DEBT_SUMMARY("tech-debt-summary");

	private final String feature;

	InsightType(String feature) {
		this.feature = feature;
	}

	public String feature() {
		return feature;
	}

}
