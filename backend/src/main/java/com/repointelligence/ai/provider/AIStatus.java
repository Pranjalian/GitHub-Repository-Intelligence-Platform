package com.repointelligence.ai.provider;

/**
 * Outcome of an AI operation. Providers return every value except {@link #SKIPPED}, which the gateway
 * uses when a guardrail (call budget) stops a call before it is made.
 */
public enum AIStatus {

	/** The model answered and the answer passed validation. */
	OK,
	/** AI is disabled, no permitted provider is configured or healthy, or the target cannot be explained. */
	UNAVAILABLE,
	/** A guardrail stopped the call, for example because the call budget is used up. */
	SKIPPED,
	/** The provider reported a quota or rate limit; it is skipped until the reset time. */
	RATE_LIMITED,
	/** The provider did not answer within the timeout. */
	TIMEOUT,
	/** The answer was not valid against the requested schema; it is never shown unvalidated. */
	INVALID_OUTPUT,
	/** Any other provider failure. */
	ERROR

}
