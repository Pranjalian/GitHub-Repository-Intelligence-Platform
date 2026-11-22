package com.repointelligence.ai.provider;

/** Whether repository excerpts stay on infrastructure the operator runs, or are sent to a hosted service. */
public enum DataHandling {

	/** Processed by a model the operator runs (e.g. Ollama). */
	LOCAL,
	/** Sent to a third-party API; used only after the operator acknowledges the provider's data handling. */
	HOSTED

}
