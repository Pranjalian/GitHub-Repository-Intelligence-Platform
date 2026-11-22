package com.repointelligence.rag.api;

import com.repointelligence.rag.indexing.EmbeddingStatus;
import com.repointelligence.rag.indexing.IndexStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Index state of one snapshot. NOT_INDEXED until an index job was requested. Keyword and "
		+ "symbol search need no model; embeddingStatus says whether semantic search is available and why not.")
public record IndexStatusDTO(UUID snapshotId, IndexStatus status, EmbeddingStatus embeddingStatus,
		String embeddingDetail, String embeddingProvider, String embeddingModel, String embeddingModelVersion,
		Integer embeddingDimensions, Integer filesIndexed, Integer filesSkipped, Integer chunkCount,
		Integer embeddedChunks,
		@Schema(description = "Embeddings found by content hash (no provider call) in the last job") Integer reusedEmbeddings,
		@Schema(description = "Embeddings requested from the provider in the last job") Integer newEmbeddings,
		String detail, List<String> warnings, Instant requestedAt, Instant startedAt, Instant completedAt,
		@Schema(description = "pgvector or in-process") String vectorStore) {
}
