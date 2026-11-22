package com.repointelligence.ai.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A provider stand-in for unit tests: fixed health and a scripted answer; records every request. */
public class FakeModelService implements AIModelService {

	private final String id;

	private final DataHandling handling;

	private ProviderHealth.Status healthStatus = ProviderHealth.Status.UP;

	private Function<AIRequest, AIResponse> answer;

	public final List<AIRequest> requests = new ArrayList<>();

	public int healthChecks;

	public FakeModelService(String id, DataHandling handling) {
		this.id = id;
		this.handling = handling;
		this.answer = request -> AIResponse.ok(id, request.model() == null ? id + "-model" : request.model(), "{}", null,
				null);
	}

	public FakeModelService answering(Function<AIRequest, AIResponse> value) {
		this.answer = value;
		return this;
	}

	public FakeModelService health(ProviderHealth.Status status) {
		this.healthStatus = status;
		return this;
	}

	@Override
	public AIResponse generate(AIRequest request) {
		requests.add(request);
		return answer.apply(request);
	}

	@Override
	public AIProviderCapabilities getCapabilities() {
		return AIProviderCapabilities.fromConfiguration(id + "-model", 8000, true);
	}

	@Override
	public ProviderHealth health() {
		healthChecks++;
		return new ProviderHealth(healthStatus, "fake", Instant.now(), null);
	}

	@Override
	public String getProviderId() {
		return id;
	}

	@Override
	public DataHandling dataHandling() {
		return handling;
	}

}
