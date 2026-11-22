package com.repointelligence.repository.infrastructure.storage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Workspaces in use outside an analysis run (e.g. a snapshot being indexed, MVP 4). The sweeper never
 * removes a leased workspace, so a long read cannot lose its files halfway. Leases are counted, so
 * nested or concurrent users of one snapshot are safe.
 */
@Component
public class WorkspaceLeases {

	private final Map<UUID, AtomicInteger> leases = new ConcurrentHashMap<>();

	/** A held lease; closing it releases it. */
	public interface Lease extends AutoCloseable {

		@Override
		void close();

	}

	public Lease acquire(UUID snapshotId) {
		leases.computeIfAbsent(snapshotId, id -> new AtomicInteger()).incrementAndGet();
		return () -> leases.computeIfPresent(snapshotId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
	}

	public boolean isLeased(UUID snapshotId) {
		AtomicInteger count = leases.get(snapshotId);
		return count != null && count.get() > 0;
	}

}
