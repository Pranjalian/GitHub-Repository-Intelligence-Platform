package com.repointelligence.rag.indexing;

import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.exception.TooManyJobsException;
import com.repointelligence.rag.api.IndexStatusDTO;
import com.repointelligence.rag.vectorstore.VectorStore;
import com.repointelligence.report.application.ReportService;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Index jobs per snapshot: request (idempotent while a job is queued or running), status, and restart
 * recovery. Indexing is optional and separate from the analysis pipeline; it needs a completed analysis
 * (for file records, modules and secret findings) but no AI.
 */
@Service
public class SnapshotIndexService {

	private static final Logger log = LoggerFactory.getLogger(SnapshotIndexService.class);

	private final SnapshotIndexRepository indexes;

	private final RepositorySnapshotRepository snapshots;

	private final ReportService reports;

	private final SnapshotIndexer indexer;

	private final ThreadPoolTaskExecutor executor;

	private final TransactionTemplate transactions;

	private final VectorStore vectorStore;

	private final com.repointelligence.rag.embedding.EmbeddingStore embeddings;

	private final Clock clock;

	public SnapshotIndexService(SnapshotIndexRepository indexes, RepositorySnapshotRepository snapshots,
			ReportService reports, SnapshotIndexer indexer, @Qualifier("ragIndexExecutor") ThreadPoolTaskExecutor executor,
			TransactionTemplate transactions, VectorStore vectorStore,
			com.repointelligence.rag.embedding.EmbeddingStore embeddings, Clock clock) {
		this.indexes = indexes;
		this.snapshots = snapshots;
		this.reports = reports;
		this.indexer = indexer;
		this.executor = executor;
		this.transactions = transactions;
		this.vectorStore = vectorStore;
		this.embeddings = embeddings;
		this.clock = clock;
	}

	public IndexStatusDTO status(UUID repositoryId, UUID snapshotId) {
		snapshots.findByIdAndRepositoryId(snapshotId, repositoryId)
			.orElseThrow(() -> new ResourceNotFoundException("Snapshot not found for this repository."));
		return indexes.findBySnapshotIdAndRepositoryId(snapshotId, repositoryId)
			.map(this::toDto)
			.orElseGet(() -> notIndexed(snapshotId));
	}

	/** Queues an index job; returns the current state when one is already queued or running. */
	public IndexStatusDTO request(UUID repositoryId, UUID snapshotId) {
		SnapshotIndex index = transactions.execute(tx -> {
			// 404 for a foreign snapshot or one without a completed analysis
			reports.scope(repositoryId, snapshotId, null);
			SnapshotIndex existing = indexes.findBySnapshotIdAndRepositoryId(snapshotId, repositoryId).orElse(null);
			if (existing != null && existing.getStatus().inFlight()) {
				return null;
			}
			if (existing == null) {
				existing = new SnapshotIndex(snapshotId, repositoryId, clock.instant());
			}
			else {
				existing.queue(clock.instant());
			}
			return indexes.save(existing);
		});
		if (index == null) {
			return status(repositoryId, snapshotId);
		}
		UUID id = index.getId();
		try {
			executor.execute(() -> indexer.run(id));
		}
		catch (TaskRejectedException ex) {
			transactions.executeWithoutResult(tx -> indexes.findById(id).ifPresent(row -> {
				row.complete(IndexStatus.FAILED, "Too many index jobs are waiting; try again later.", List.of(),
						clock.instant());
			}));
			throw new TooManyJobsException("Too many index jobs are waiting; try again later.");
		}
		return toDto(index);
	}

	/** Jobs that were queued or running when the application stopped cannot resume; say so. */
	@EventListener(ApplicationReadyEvent.class)
	public void failInterruptedJobs() {
		transactions.executeWithoutResult(tx -> {
			for (SnapshotIndex index : indexes.findByStatusIn(List.of(IndexStatus.QUEUED, IndexStatus.INDEXING))) {
				index.complete(IndexStatus.FAILED, "Interrupted by an application restart; index the snapshot again.",
						List.of(), clock.instant());
				log.info("Index job of snapshot {} was interrupted by a restart", index.getSnapshotId());
			}
		});
	}

	/** Vectors no chunk refers to any more (deleted repositories or re-indexed snapshots) are removed hourly. */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void purgeUnreferencedEmbeddings() {
		int removed = embeddings.purgeUnreferenced();
		if (removed > 0) {
			log.info("Removed {} unreferenced embedding(s)", removed);
		}
	}

	IndexStatusDTO notIndexed(UUID snapshotId) {
		return new IndexStatusDTO(snapshotId, IndexStatus.NOT_INDEXED, null, null, null, null, null, null, null, null,
				null, null, null, null, "This snapshot has not been indexed yet.", List.of(), null, null, null,
				vectorStore.name());
	}

	IndexStatusDTO toDto(SnapshotIndex index) {
		return new IndexStatusDTO(index.getSnapshotId(), index.getStatus(), index.getEmbeddingStatus(),
				index.getEmbeddingDetail(), index.getEmbeddingProvider(), index.getEmbeddingModel(),
				index.getEmbeddingModelVersion(), index.getEmbeddingDimensions(), index.getFilesIndexed(),
				index.getFilesSkipped(), index.getChunkCount(), index.getEmbeddedChunks(), index.getReusedEmbeddings(),
				index.getNewEmbeddings(), index.getDetail(), index.getWarnings(), index.getRequestedAt(),
				index.getStartedAt(), index.getCompletedAt(), vectorStore.name());
	}

}
