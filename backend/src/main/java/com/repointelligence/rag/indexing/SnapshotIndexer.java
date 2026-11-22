package com.repointelligence.rag.indexing;

import com.repointelligence.ai.application.AIGateway;
import com.repointelligence.ai.application.PromptScrubber;
import com.repointelligence.ai.application.SecretRegion;
import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.analysis.domain.ModuleRecord;
import com.repointelligence.analysis.generic.GenericSecretScanAnalyzer;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.ModuleRecordRepository;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.security.SecretRedactor;
import com.repointelligence.rag.RagProperties;
import com.repointelligence.rag.chunking.ChunkDraft;
import com.repointelligence.rag.chunking.CodeChunker;
import com.repointelligence.rag.chunking.SourceFile;
import com.repointelligence.rag.embedding.EmbeddingService;
import com.repointelligence.report.application.ReportService;
import com.repointelligence.repository.application.SnapshotSourceService;
import com.repointelligence.repository.domain.FileRecord;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.persistence.FileRecordRepository;
import com.repointelligence.repository.infrastructure.storage.WorkspaceLeases;
import com.repointelligence.repository.infrastructure.storage.WorkspaceManager;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One index job: read the snapshot's text files from its workspace (rebuilt from the commit's tarball when
 * it was deleted), scrub secrets exactly like AI prompts, chunk, store the chunks with their keyword index,
 * then add embeddings for content hashes that have none for the current model. The workspace is leased
 * for the whole job so the sweeper cannot remove it halfway. Repository files are only read as text.
 */
@Component
public class SnapshotIndexer {

	private static final Logger log = LoggerFactory.getLogger(SnapshotIndexer.class);

	/**
	 * File classes worth answering questions about. RESOURCE files are included when they are in a known text
	 * language (application.yml, templates, SQL); OTHER and unknown resources are skipped.
	 */
	private static final Set<FileType> INDEXED_TYPES = Set.of(FileType.SOURCE, FileType.TEST, FileType.CONFIG,
			FileType.BUILD, FileType.DOCS);

	private static final Set<RunStatus> IN_FLIGHT_RUNS = Set.of(RunStatus.QUEUED, RunStatus.INGESTING,
			RunStatus.ANALYZING);

	private final SnapshotIndexRepository indexes;

	private final AnalysisRunRepository runs;

	private final ReportService reports;

	private final FileRecordRepository files;

	private final ModuleRecordRepository modules;

	private final SnapshotSourceService sources;

	private final WorkspaceManager workspaces;

	private final WorkspaceLeases leases;

	private final CodeChunker chunker;

	private final ChunkStore chunks;

	private final EmbeddingService embeddings;

	private final AIGateway gateway;

	private final PromptScrubber scrubber;

	private final RagProperties.Indexing settings;

	private final AIProperties ai;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public SnapshotIndexer(SnapshotIndexRepository indexes, ReportService reports, FileRecordRepository files,
			ModuleRecordRepository modules, SnapshotSourceService sources, WorkspaceManager workspaces,
			WorkspaceLeases leases, CodeChunker chunker, ChunkStore chunks, EmbeddingService embeddings,
			AIGateway gateway, GenericSecretScanAnalyzer secretScanner, RagProperties properties, AIProperties ai,
			TransactionTemplate transactions, Clock clock, AnalysisRunRepository runs) {
		this.indexes = indexes;
		this.runs = runs;
		this.reports = reports;
		this.files = files;
		this.modules = modules;
		this.sources = sources;
		this.workspaces = workspaces;
		this.leases = leases;
		this.chunker = chunker;
		this.chunks = chunks;
		this.embeddings = embeddings;
		this.gateway = gateway;
		this.scrubber = new PromptScrubber(secretScanner);
		this.settings = properties.indexing();
		this.ai = ai;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** Plain values read once at the start of a job (no lazy entities outside the transaction). */
	private record Job(UUID indexId, UUID snapshotId, UUID repositoryId, UUID runId, boolean hostedPermitted,
			List<FileRecord> files, List<String> modulePaths, List<SecretRegion> regions) {
	}

	public void run(UUID indexId) {
		Job job;
		try {
			job = transactions.execute(tx -> start(indexId));
		}
		catch (RuntimeException ex) {
			log.warn("Index job {} could not start: {}", indexId, ex.getClass().getSimpleName());
			finish(indexId, IndexStatus.FAILED, "The index job could not start (" + ex.getClass().getSimpleName() + ").",
					List.of());
			return;
		}
		if (job == null) {
			return;
		}
		boolean existed = workspaces.exists(job.snapshotId());
		try (WorkspaceLeases.Lease lease = leases.acquire(job.snapshotId())) {
			index(job);
		}
		catch (RuntimeException ex) {
			log.warn("Index job of snapshot {} failed: {}", job.snapshotId(), ex.getClass().getSimpleName());
			finish(indexId, IndexStatus.FAILED,
					"Indexing failed (" + ex.getClass().getSimpleName() + ": " + SecretRedactor.redact(String.valueOf(ex.getMessage())) + ").",
					List.of());
		}
		finally {
			if (!existed) {
				removeRestoredWorkspace(job.snapshotId());
			}
		}
	}

	/**
	 * A workspace rebuilt only for this job is removed again: everything was read once, and keeping a whole
	 * extracted repository until the next hourly sweep serves nobody. It stays when an analysis or a reader
	 * (insight excerpt, another job) is using it.
	 */
	private void removeRestoredWorkspace(UUID snapshotId) {
		try {
			if (!leases.isLeased(snapshotId) && !runs.findSnapshotIdsByStatusIn(IN_FLIGHT_RUNS).contains(snapshotId)) {
				workspaces.delete(snapshotId);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not remove the workspace of snapshot {} after indexing: {}", snapshotId,
					ex.getClass().getSimpleName());
		}
	}

	private Job start(UUID indexId) {
		SnapshotIndex index = indexes.findById(indexId).orElse(null);
		if (index == null || index.getStatus() != IndexStatus.QUEUED) {
			return null;
		}
		index.start(clock.instant());
		ReportService.ReportScope scope = reports.scope(index.getRepositoryId(), index.getSnapshotId(), null);
		UUID runId = scope.run().getId();
		boolean hosted = "public".equalsIgnoreCase(scope.snapshot().getRepository().getVisibility())
				|| ai.allowHostedForPrivateRepositories();
		List<FileRecord> fileList = files.findBySnapshotId(index.getSnapshotId());
		List<String> modulePaths = modules.findBySnapshotIdAndRunId(index.getSnapshotId(), runId)
			.stream()
			.map(ModuleRecord::getPath)
			.distinct()
			.toList();
		return new Job(indexId, index.getSnapshotId(), index.getRepositoryId(), runId, hosted, fileList, modulePaths,
				gateway.secretRegions(index.getSnapshotId(), runId));
	}

	private void index(Job job) {
		String problem = sources.ensureWorkspace(job.snapshotId(), settings.allowDownload());
		if (problem != null) {
			finish(job.indexId(), IndexStatus.FAILED, "The source is not available: " + problem + ".", List.of());
			return;
		}
		WorkspaceView view = workspaces.view(job.snapshotId());
		List<PathMatcher> excluded = settings.excludedFiles()
			.stream()
			.map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
			.toList();
		List<FileRecord> candidates = job.files()
			.stream()
			.filter(f -> !f.isBinary() && f.getSkippedReason() == null && (INDEXED_TYPES.contains(f.getFileType())
					|| f.getFileType() == FileType.RESOURCE && f.getLanguage() != null))
			.sorted(Comparator.comparing(FileRecord::getPath))
			.toList();
		List<String> warnings = new ArrayList<>();
		List<ChunkStore.NewChunk> stored = new ArrayList<>();
		int indexed = 0;
		int skipped = job.files().size() - candidates.size();
		int tooLarge = 0;
		int unreadable = 0;
		int excludedCount = 0;
		int redactedFiles = 0;
		boolean capped = false;
		for (FileRecord file : candidates) {
			String name = file.getPath().substring(file.getPath().lastIndexOf('/') + 1);
			if (excluded.stream().anyMatch(m -> m.matches(Path.of(name)))) {
				excludedCount++;
				skipped++;
				continue;
			}
			if (file.getSizeBytes() > settings.maxFileKb() * 1024L) {
				tooLarge++;
				skipped++;
				continue;
			}
			if (indexed >= settings.maxFiles() || capped) {
				capped = true;
				skipped++;
				continue;
			}
			String text;
			try {
				if (!view.exists(file.getPath())) {
					unreadable++;
					skipped++;
					continue;
				}
				text = view.readString(file.getPath(), settings.maxFileKb() * 1024L);
			}
			catch (java.io.IOException | RuntimeException ex) {
				unreadable++;
				skipped++;
				continue;
			}
			PromptScrubber.Scrubbed scrubbed = scrubber.scrub(
					List.of(new EvidenceBlock("F", PromptScrubber.SOURCE, file.getPath(), 1, null, text, null, false)),
					job.regions());
			String clean = scrubbed.blocks().get(0).text();
			boolean redacted = scrubbed.withheldLines() > 0 || !clean.equals(text);
			if (redacted) {
				redactedFiles++;
			}
			List<ChunkDraft> drafts = chunker.chunk(new SourceFile(file.getPath(), file.getLanguage(),
					file.getFileType().name(), moduleOf(file.getPath(), job.modulePaths()), clean));
			for (ChunkDraft draft : drafts) {
				if (stored.size() >= settings.maxChunks()) {
					capped = true;
					break;
				}
				boolean chunkRedacted = redacted
						&& (draft.content().contains("[line withheld") || draft.content().contains(SecretRedactor.MASK));
				stored.add(new ChunkStore.NewChunk(draft, sha256(draft.indexedText()), chunkRedacted));
			}
			indexed++;
		}
		if (tooLarge > 0) {
			warnings.add(tooLarge + " file(s) larger than " + settings.maxFileKb() + " KB were not indexed (rag.indexing.max-file-kb).");
		}
		if (excludedCount > 0) {
			warnings.add(excludedCount + " lockfile(s), minified or generated file(s) were not indexed (rag.indexing.excluded-files).");
		}
		if (unreadable > 0) {
			warnings.add(unreadable + " file(s) were not in the extracted source or could not be read.");
		}
		if (capped) {
			warnings.add("Indexing stopped at the limit of " + settings.maxFiles() + " files or " + settings.maxChunks()
					+ " chunks (rag.indexing.max-files, max-chunks); later files (by path) are not searchable.");
		}
		if (redactedFiles > 0) {
			warnings.add(redactedFiles + " file(s) contained possible secrets; those lines are withheld or masked in the index.");
		}
		chunks.replace(job.snapshotId(), job.repositoryId(), stored);
		int chunkCount = stored.size();
		int indexedFiles = indexed;
		int skippedFiles = skipped;
		transactions.executeWithoutResult(tx -> indexes.findById(job.indexId())
			.ifPresent(row -> row.counts(indexedFiles, skippedFiles, chunkCount)));

		EmbeddingService.Outcome outcome = embeddings.embedSnapshot(job.snapshotId(), job.repositoryId(),
				job.hostedPermitted(), () -> false);
		warnings.addAll(outcome.warnings());
		transactions.executeWithoutResult(tx -> indexes.findById(job.indexId())
			.ifPresent(row -> row.embeddings(outcome.status(), outcome.detail(), outcome.provider(), outcome.model(),
					outcome.version(), outcome.dimensions(), outcome.embedded(), outcome.reused(), outcome.created())));
		// redacted lines and excluded lockfiles are expected and only reported; missing files or embeddings that
		// were configured but did not work are warnings
		boolean degraded = tooLarge > 0 || unreadable > 0 || capped || outcome.status() == EmbeddingStatus.PARTIAL
				|| outcome.status() == EmbeddingStatus.FAILED || outcome.status() == EmbeddingStatus.UNAVAILABLE;
		String detail = "Indexed " + chunkCount + " chunk(s) from " + indexedFiles + " file(s); " + skippedFiles
				+ " file(s) skipped (binary, resources, too large or excluded).";
		finish(job.indexId(), degraded ? IndexStatus.COMPLETED_WITH_WARNINGS : IndexStatus.COMPLETED, detail, warnings);
		log.info("Indexed snapshot {}: {} chunks from {} files; embeddings {}", job.snapshotId(), chunkCount, indexedFiles,
				outcome.status());
	}

	private void finish(UUID indexId, IndexStatus status, String detail, List<String> warnings) {
		transactions.executeWithoutResult(
				tx -> indexes.findById(indexId).ifPresent(row -> row.complete(status, detail, warnings, clock.instant())));
	}

	/** The deepest module whose path contains the file; the root module is {@code .} or empty. */
	static String moduleOf(String path, List<String> modulePaths) {
		String best = null;
		for (String module : modulePaths) {
			String prefix = module == null || module.isEmpty() || module.equals(".") ? "" : module.endsWith("/") ? module : module + "/";
			if (path.startsWith(prefix) && (best == null || prefix.length() > normalized(best).length())) {
				best = module;
			}
		}
		return best;
	}

	private static String normalized(String module) {
		return module == null || module.isEmpty() || module.equals(".") ? "" : module.endsWith("/") ? module : module + "/";
	}

	static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
