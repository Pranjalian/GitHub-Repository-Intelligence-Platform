package com.repointelligence.repository.application;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.exception.AnalysisLimitException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.exception.UpstreamRateLimitException;
import com.repointelligence.common.model.IngestionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.security.SecretRedactor;
import com.repointelligence.repository.domain.FileRecord;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.infrastructure.github.GitHubClient;
import com.repointelligence.repository.infrastructure.persistence.FileRecordRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.storage.ExtractedEntry;
import com.repointelligence.repository.infrastructure.storage.ExtractionLimits;
import com.repointelligence.repository.infrastructure.storage.ExtractionResult;
import com.repointelligence.repository.infrastructure.storage.WorkspaceManager;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings a snapshot's source into a bounded local workspace and catalogues its files. Network and
 * disk work happens outside database transactions; only short status and insert steps use them.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private static final int FLUSH_BATCH = 200;

	private static final int MAX_REASON_LENGTH = 500;

	private final GitHubClient github;

	private final WorkspaceManager workspaces;

	private final FileClassifier classifier;

	private final IngestionProperties properties;

	private final AnalysisRunRepository runs;

	private final RepositorySnapshotRepository snapshots;

	private final FileRecordRepository files;

	private final EntityManager entityManager;

	private final Clock clock;

	private final TransactionTemplate transaction;

	public IngestionService(GitHubClient github, WorkspaceManager workspaces, FileClassifier classifier,
			IngestionProperties properties, AnalysisRunRepository runs, RepositorySnapshotRepository snapshots,
			FileRecordRepository files, EntityManager entityManager, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.github = github;
		this.workspaces = workspaces;
		this.classifier = classifier;
		this.properties = properties;
		this.runs = runs;
		this.snapshots = snapshots;
		this.files = files;
		this.entityManager = entityManager;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Ingests the snapshot of a run. If the snapshot is already ingested and its workspace is still
	 * present, nothing is downloaded. If it is ingested but the workspace is gone, the tarball is
	 * downloaded again (immutable per SHA) and the existing file records are reused.
	 */
	public IngestionResult ingest(UUID runId) {
		Target target = transaction.execute(status -> markIngesting(runId));
		UUID snapshotId = target.snapshotId();

		if (target.alreadyIngested() && workspaces.exists(snapshotId)) {
			return new IngestionResult(IngestionResult.Status.INGESTED, runId, snapshotId,
					workspaces.workspaceDir(snapshotId), null);
		}
		Path tarball = null;
		try {
			tarball = workspaces.downloadPath(snapshotId);
			github.downloadTarball(target.owner(), target.name(), target.sha(), tarball,
					properties.maxTarballSizeMb() * 1024L * 1024L);
			ExtractionResult extraction = workspaces.extract(tarball, snapshotId, limits());
			if (!target.alreadyIngested()) {
				transaction.executeWithoutResult(status -> recordFiles(snapshotId, extraction));
			}
			return new IngestionResult(IngestionResult.Status.INGESTED, runId, snapshotId,
					workspaces.workspaceDir(snapshotId), null);
		}
		catch (AnalysisLimitException ex) {
			// Limit breaches and unsafe archives: the repository is rejected with a readable reason.
			return fail(runId, snapshotId, IngestionResult.Status.REJECTED, ex.getMessage(), null);
		}
		catch (UpstreamRateLimitException ex) {
			return fail(runId, snapshotId, IngestionResult.Status.FAILED, ex.getMessage(), ex.getResetAt());
		}
		catch (ResourceNotFoundException ex) {
			return fail(runId, snapshotId, IngestionResult.Status.FAILED,
					"The repository or commit is no longer available on GitHub.", null);
		}
		catch (RuntimeException | IOException ex) {
			log.error("Ingestion of snapshot {} failed", snapshotId, ex);
			return fail(runId, snapshotId, IngestionResult.Status.FAILED,
					"Ingestion failed unexpectedly (" + ex.getClass().getSimpleName() + ").", null);
		}
		finally {
			deleteQuietly(tarball);
		}
	}

	private record Target(UUID snapshotId, String owner, String name, String sha, boolean alreadyIngested) {
	}

	private Target markIngesting(UUID runId) {
		AnalysisRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Run not found."));
		RepositorySnapshot snapshot = run.getSnapshot();
		boolean alreadyIngested = snapshot.getIngestionStatus() == IngestionStatus.INGESTED;
		run.setStatus(RunStatus.INGESTING);
		if (run.getStartedAt() == null) {
			run.setStartedAt(clock.instant());
		}
		if (!alreadyIngested) {
			snapshot.setIngestionStatus(IngestionStatus.INGESTING);
			snapshot.setIngestionError(null);
		}
		return new Target(snapshot.getId(), snapshot.getRepository().getOwner(), snapshot.getRepository().getName(),
				snapshot.getCommitSha(), alreadyIngested);
	}

	private ExtractionLimits limits() {
		return new ExtractionLimits(properties.maxFileSizeKb() * 1024L, properties.maxExtractedSizeMb() * 1024L * 1024L,
				properties.maxFileCount(), properties.maxFileCount() * 10, properties.excludedDirectories(),
				properties.excludedExceptions());
	}

	/** Batch-inserts the file records (flushing in chunks to bound memory) and marks the snapshot ingested. */
	private void recordFiles(UUID snapshotId, ExtractionResult extraction) {
		long totalBytes = 0;
		int pending = 0;
		for (ExtractedEntry entry : extraction.entries()) {
			RepositorySnapshot snapshotRef = entityManager.getReference(RepositorySnapshot.class, snapshotId);
			files.save(toRecord(snapshotRef, entry));
			totalBytes += entry.sizeBytes();
			if (++pending % FLUSH_BATCH == 0) {
				entityManager.flush();
				entityManager.clear();
			}
		}
		entityManager.flush();
		entityManager.clear();
		RepositorySnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
		snapshot.setIngestionStatus(IngestionStatus.INGESTED);
		snapshot.setFileCount(extraction.entries().size());
		snapshot.setTotalBytes(totalBytes);
		snapshot.setIngestedAt(clock.instant());
		snapshot.setIngestionError(null);
	}

	private FileRecord toRecord(RepositorySnapshot snapshot, ExtractedEntry entry) {
		FileRecord record = new FileRecord(snapshot, entry.path(), classifier.typeOf(entry.path()), entry.sizeBytes());
		record.setLanguage(classifier.languageOf(entry.path()));
		record.setContentHash(entry.sha256());
		record.setBinary(entry.binary());
		record.setSkippedReason(entry.skippedReason());
		return record;
	}

	private IngestionResult fail(UUID runId, UUID snapshotId, IngestionResult.Status status, String reason,
			Instant retryAfter) {
		String safeReason = truncate(SecretRedactor.redact(reason));
		workspaces.delete(snapshotId);
		transaction.executeWithoutResult(tx -> {
			AnalysisRun run = runs.findById(runId).orElseThrow();
			run.setStatus(RunStatus.FAILED);
			run.setFailureReason(safeReason);
			run.setRetryAfter(retryAfter);
			run.setCompletedAt(clock.instant());
			RepositorySnapshot snapshot = run.getSnapshot();
			if (snapshot.getIngestionStatus() != IngestionStatus.INGESTED) {
				snapshot.setIngestionStatus(
						status == IngestionResult.Status.REJECTED ? IngestionStatus.REJECTED : IngestionStatus.FAILED);
				snapshot.setIngestionError(safeReason);
			}
		});
		return new IngestionResult(status, runId, snapshotId, null, safeReason);
	}

	private static String truncate(String text) {
		return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ex) {
			log.debug("Could not delete temporary download");
		}
	}

}
