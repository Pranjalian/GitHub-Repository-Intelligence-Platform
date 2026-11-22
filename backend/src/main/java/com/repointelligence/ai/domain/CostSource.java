package com.repointelligence.ai.domain;

/** What an estimated cost is based on. There is no cost at all unless pricing is configured. */
public enum CostSource {

	/** Configured prices times provider-reported token counts. */
	REPORTED_TOKENS,
	/** Configured prices times locally estimated token counts. */
	ESTIMATED_TOKENS

}
