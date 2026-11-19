package com.repointelligence.analysis.spi;

/** Thrown by {@link CancellationToken#throwIfCancelled()}; the orchestrator maps it to CANCELLED. */
public class AnalysisCancelledException extends RuntimeException {

	public AnalysisCancelledException() {
		super("Analysis was cancelled");
	}

}
