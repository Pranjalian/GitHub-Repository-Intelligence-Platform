package com.repointelligence.ai.provider;

/**
 * The provider-neutral model interface. Only {@code ai.provider} and its sub-packages know concrete
 * providers; features talk to the AI gateway, which talks to this interface. Implementations must
 * apply the configured timeout and retry limit, never retry a rate limit, and map every failure to a
 * status instead of throwing.
 */
public interface AIModelService {

	/** Never throws for provider failures; returns a response with a non-OK status instead. */
	AIResponse generate(AIRequest request);

	AIProviderCapabilities getCapabilities();

	/** A cheap reachability and model check; never throws. */
	ProviderHealth health();

	String getProviderId();

	DataHandling dataHandling();

}
