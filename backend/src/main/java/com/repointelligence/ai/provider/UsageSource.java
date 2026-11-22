package com.repointelligence.ai.provider;

/** Where token counts come from. Estimates are never presented as provider-reported numbers. */
public enum UsageSource {

	/** Counted by the provider and returned with the response. */
	REPORTED,
	/** Estimated locally (characters per token heuristic); the provider reported nothing. */
	ESTIMATED,
	/** Not known. */
	UNKNOWN

}
