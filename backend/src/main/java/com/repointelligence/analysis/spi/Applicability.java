package com.repointelligence.analysis.spi;

import java.util.Objects;

/**
 * Whether an analyzer can run on a snapshot. {@code NOT_APPLICABLE}: nothing for it to analyze (no
 * pom.xml for the Maven analyzer). {@code UNAVAILABLE}: it would apply but cannot run (a required tool
 * is missing); the reason is shown to users. {@code DISABLED} is set by the registry from
 * configuration, never by an analyzer.
 */
public record Applicability(Kind kind, String reason) {

	public enum Kind {

		APPLICABLE, NOT_APPLICABLE, UNAVAILABLE, DISABLED

	}

	public Applicability {
		Objects.requireNonNull(kind, "kind");
	}

	private static final Applicability YES = new Applicability(Kind.APPLICABLE, null);

	private static final Applicability NO = new Applicability(Kind.NOT_APPLICABLE, null);

	public static Applicability applicable() {
		return YES;
	}

	public static Applicability notApplicable() {
		return NO;
	}

	public static Applicability unavailable(String reason) {
		return new Applicability(Kind.UNAVAILABLE, reason);
	}

	public static Applicability disabled() {
		return new Applicability(Kind.DISABLED, "disabled by configuration");
	}

	/** Switched off (not configured), with the reason shown to users, e.g. "vulnerability lookup disabled". */
	public static Applicability disabled(String reason) {
		return new Applicability(Kind.DISABLED, reason);
	}

	public boolean isApplicable() {
		return kind == Kind.APPLICABLE;
	}

}
