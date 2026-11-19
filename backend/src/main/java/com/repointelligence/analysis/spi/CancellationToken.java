package com.repointelligence.analysis.spi;

/** Cooperative cancellation: analyzers check it between files and stop when it is set. */
public interface CancellationToken {

	CancellationToken NONE = () -> false;

	boolean isCancelled();

	default void throwIfCancelled() {
		if (isCancelled()) {
			throw new AnalysisCancelledException();
		}
	}

}
