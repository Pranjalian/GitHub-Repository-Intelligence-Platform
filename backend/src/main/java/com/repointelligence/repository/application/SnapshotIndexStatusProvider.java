package com.repointelligence.repository.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port for the search-index state of snapshots. The repository module cannot depend on the RAG module
 * (RAG is layered on top), so the RAG module implements this interface and the repository module only
 * sees status names. Snapshots without an index job are simply absent from the result.
 */
public interface SnapshotIndexStatusProvider {

	Map<UUID, String> statusesOf(Collection<UUID> snapshotIds);

}
