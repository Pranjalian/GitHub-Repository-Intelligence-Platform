package com.repointelligence.rag.vectorstore;

import com.repointelligence.rag.retrieval.ChunkFilter;
import java.util.List;
import java.util.UUID;

/**
 * Nearest chunks of one snapshot by cosine similarity. Implementations always filter by snapshot and
 * repository and compare only vectors of the given model, version and dimension.
 */
public interface VectorStore {

	/** {@code pgvector} or {@code in-process}. */
	String name();

	/** A chunk and its cosine similarity to the query (1 = same direction). */
	record VectorHit(UUID chunkId, double similarity) {
	}

	/** Hits best first, or the reason the search could not run. */
	record VectorSearch(List<VectorHit> hits, String unavailableReason) {

		public static VectorSearch unavailable(String reason) {
			return new VectorSearch(List.of(), reason);
		}

	}

	VectorSearch nearest(UUID snapshotId, UUID repositoryId, String model, String version, float[] query,
			ChunkFilter filter, int k);

}
