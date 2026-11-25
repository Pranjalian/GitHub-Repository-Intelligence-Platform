package com.repointelligence.rag.indexing;

import com.repointelligence.repository.application.SnapshotIndexStatusProvider;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Lets the repository module show index state without depending on the RAG module. */
@Component
class SnapshotIndexStatusAdapter implements SnapshotIndexStatusProvider {

	private final SnapshotIndexRepository indexes;

	SnapshotIndexStatusAdapter(SnapshotIndexRepository indexes) {
		this.indexes = indexes;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> statusesOf(Collection<UUID> snapshotIds) {
		Map<UUID, String> result = new HashMap<>();
		if (!snapshotIds.isEmpty()) {
			indexes.findBySnapshotIdIn(snapshotIds).forEach(i -> result.put(i.getSnapshotId(), i.getStatus().name()));
		}
		return result;
	}

}
