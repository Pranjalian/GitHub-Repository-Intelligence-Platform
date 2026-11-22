package com.repointelligence.ai.application;

/**
 * Character-based token estimate ({@code ai.token-estimate.chars-per-token}). Used for budgeting
 * prompts and, when a provider reports nothing, recorded as ESTIMATED usage; never presented as a
 * reported count.
 */
public final class TokenEstimator {

	private final double charsPerToken;

	public TokenEstimator(double charsPerToken) {
		this.charsPerToken = charsPerToken;
	}

	public int estimate(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return (int) Math.ceil(text.length() / charsPerToken);
	}

	/** Largest number of characters that fits in {@code tokens}. */
	public int charsFor(int tokens) {
		return (int) Math.floor(Math.max(0, tokens) * charsPerToken);
	}

}
