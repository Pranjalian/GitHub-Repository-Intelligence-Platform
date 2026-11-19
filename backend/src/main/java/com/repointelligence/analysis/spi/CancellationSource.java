package com.repointelligence.analysis.spi;

import java.util.concurrent.atomic.AtomicBoolean;

/** The owner side of a {@link CancellationToken}: the orchestrator cancels, analyzers only observe. */
public final class CancellationSource implements CancellationToken {

	private final AtomicBoolean cancelled = new AtomicBoolean();

	public void cancel() {
		cancelled.set(true);
	}

	@Override
	public boolean isCancelled() {
		return cancelled.get();
	}

}
