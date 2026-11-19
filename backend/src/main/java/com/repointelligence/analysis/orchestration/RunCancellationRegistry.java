package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.spi.CancellationSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The cancellation tokens of the runs currently executing in this process. */
@Component
public class RunCancellationRegistry {

	private final Map<UUID, CancellationSource> sources = new ConcurrentHashMap<>();

	CancellationSource register(UUID runId) {
		return sources.computeIfAbsent(runId, id -> new CancellationSource());
	}

	void remove(UUID runId) {
		sources.remove(runId);
	}

	/** @return true if the run is executing here and was signalled */
	public boolean cancel(UUID runId) {
		CancellationSource source = sources.get(runId);
		if (source == null) {
			return false;
		}
		source.cancel();
		return true;
	}

	public boolean isRunning(UUID runId) {
		return sources.containsKey(runId);
	}

}
