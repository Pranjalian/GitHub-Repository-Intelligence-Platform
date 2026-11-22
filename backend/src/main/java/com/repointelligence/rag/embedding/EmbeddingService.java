package com.repointelligence.rag.embedding;

import com.repointelligence.ai.application.AIUsageService;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.EmbeddingPurpose;
import com.repointelligence.ai.provider.EmbeddingResponse;
import com.repointelligence.ai.provider.EmbeddingRouter;
import com.repointelligence.rag.indexing.ChunkStore;
import com.repointelligence.rag.indexing.EmbeddingStatus;
import com.repointelligence.rag.indexing.StoredChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Embeddings for a snapshot's chunks and for queries. Only content hashes without a vector for the current
 * model and version are sent (so re-indexing a new commit embeds only changed chunks), in batches of
 * {@code ai.embedding.batch-size}, at most {@code ai.embedding.max-batches-per-index} requests per job.
 * Every provider call gets a usage record (feature {@code embed-chunks} or {@code embed-query}); embedding
 * calls do not count against the insight budget of an analysis run. Chunk text was secret-scrubbed when it
 * was stored, before it can reach any provider.
 */
@Service
public class EmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

	static final String INDEX_FEATURE = "embed-chunks";

	static final String QUERY_FEATURE = "embed-query";

	/** Usage records name a prompt version; embeddings have none, so they carry this fixed label. */
	static final String VERSION_LABEL = "embedding";

	private final EmbeddingRouter router;

	private final ChunkStore chunks;

	private final EmbeddingStore store;

	private final AIUsageService usage;

	private final AIProperties.Embedding settings;

	public EmbeddingService(EmbeddingRouter router, ChunkStore chunks, EmbeddingStore store, AIUsageService usage,
			AIProperties properties) {
		this.router = router;
		this.chunks = chunks;
		this.store = store;
		this.usage = usage;
		this.settings = properties.embedding();
	}

	/** What an embedding pass did. */
	public record Outcome(EmbeddingStatus status, String detail, String provider, String model, String version,
			Integer dimensions, int embedded, int reused, int created, List<String> warnings) {
	}

	public Outcome embedSnapshot(UUID snapshotId, UUID repositoryId, boolean hostedPermitted, BooleanSupplier cancelled) {
		EmbeddingRouter.Selection selection = router.select(hostedPermitted);
		if (!selection.available()) {
			EmbeddingStatus status = selection.notConfigured() ? EmbeddingStatus.NOT_CONFIGURED : EmbeddingStatus.UNAVAILABLE;
			return new Outcome(status, "No embeddings: " + selection.reason() + ". Keyword and symbol search still work.",
					selection.provider(), selection.model(), null, null, 0, 0, 0, List.of());
		}
		String model = selection.model();
		String version = selection.modelVersion();
		int total = chunks.count(snapshotId, repositoryId);
		int before = chunks.embeddedCount(snapshotId, repositoryId, model, version);
		List<StoredChunk> missing = chunks.missingEmbeddings(snapshotId, repositoryId, model, version);
		Integer dimensions = store.dimensions(model, version);
		List<String> warnings = new ArrayList<>();
		int truncated = 0;
		int created = 0;
		int batches = 0;
		String failure = null;
		AIStatus failureStatus = null;
		for (int from = 0; from < missing.size(); from += settings.batchSize()) {
			if (cancelled.getAsBoolean()) {
				failure = "the index job was stopped";
				break;
			}
			if (batches >= settings.maxBatchesPerIndex()) {
				failure = "the request budget of " + settings.maxBatchesPerIndex()
						+ " embedding calls per index job was reached (ai.embedding.max-batches-per-index)";
				break;
			}
			List<StoredChunk> batch = missing.subList(from, Math.min(missing.size(), from + settings.batchSize()));
			List<String> texts = new ArrayList<>();
			for (StoredChunk chunk : batch) {
				String text = chunk.draft().indexedText();
				if (text.length() > settings.maxInputChars()) {
					text = text.substring(0, settings.maxInputChars());
					truncated++;
				}
				texts.add(text);
			}
			long started = System.nanoTime();
			EmbeddingResponse response = selection.service().embed(texts, EmbeddingPurpose.DOCUMENT);
			batches++;
			record(selection.provider(), model, INDEX_FEATURE, repositoryId, snapshotId, response, texts,
					(System.nanoTime() - started) / 1_000_000);
			router.report(selection.provider(), response);
			if (!response.isOk()) {
				failure = response.detail();
				failureStatus = response.status();
				break;
			}
			if (dimensions == null) {
				dimensions = response.vectors().get(0).length;
			}
			if (response.vectors().get(0).length != dimensions) {
				failure = "the model returned " + response.vectors().get(0).length + " dimensions, but " + model + " ("
						+ version + ") vectors already have " + dimensions;
				failureStatus = AIStatus.INVALID_OUTPUT;
				break;
			}
			List<EmbeddingStore.Vector> vectors = new ArrayList<>();
			for (int i = 0; i < batch.size(); i++) {
				vectors.add(new EmbeddingStore.Vector(batch.get(i).contentHash(), response.vectors().get(i)));
			}
			store.save(model, version, vectors);
			created += vectors.size();
		}
		if (truncated > 0) {
			warnings.add(truncated + " chunk(s) were longer than " + settings.maxInputChars()
					+ " characters and were embedded from their beginning only (ai.embedding.max-input-chars).");
		}
		int embedded = chunks.embeddedCount(snapshotId, repositoryId, model, version);
		EmbeddingStatus status;
		String detail;
		if (embedded == total) {
			status = EmbeddingStatus.COMPLETED;
			detail = "Embedded " + total + " chunk(s) with " + model + " (" + version + "): " + created + " new, "
					+ before + " reused by content hash.";
		}
		else if (embedded > 0) {
			status = EmbeddingStatus.PARTIAL;
			detail = embedded + " of " + total + " chunk(s) have embeddings; stopped because " + failure
					+ ". The rest are found by keyword search only; index again to continue.";
		}
		else {
			status = failureStatus == AIStatus.UNAVAILABLE || failureStatus == AIStatus.RATE_LIMITED
					|| failureStatus == AIStatus.TIMEOUT ? EmbeddingStatus.UNAVAILABLE : EmbeddingStatus.FAILED;
			detail = "No embeddings: " + failure + ". Keyword and symbol search still work.";
		}
		log.info("Embeddings for snapshot {}: {} of {} chunks ({} new, {} reused)", snapshotId, embedded, total, created,
				before);
		return new Outcome(status, detail, selection.provider(), model, version, dimensions, embedded, before, created,
				warnings);
	}

	/** A query vector, or why there is none. */
	public record QueryVector(float[] vector, String unavailableReason) {

		public boolean available() {
			return vector != null;
		}

	}

	/**
	 * Embeds a query with exactly the model and version the index was built with; a different current model
	 * means semantic search is unavailable until the snapshot is indexed again (vectors are never mixed).
	 */
	public QueryVector embedQuery(String text, String indexModel, String indexVersion, UUID repositoryId,
			UUID snapshotId, boolean hostedPermitted) {
		if (indexModel == null || indexVersion == null) {
			return new QueryVector(null, "the index has no embeddings");
		}
		EmbeddingRouter.Selection selection = router.select(hostedPermitted);
		if (!selection.available()) {
			return new QueryVector(null, selection.reason());
		}
		if (!indexModel.equals(selection.model()) || !indexVersion.equals(selection.modelVersion())) {
			return new QueryVector(null, "the index was embedded with " + indexModel + " (" + indexVersion
					+ ") but the current embedding model is " + selection.model() + " (" + selection.modelVersion()
					+ "); index the snapshot again");
		}
		String input = text.length() > settings.maxInputChars() ? text.substring(0, settings.maxInputChars()) : text;
		long started = System.nanoTime();
		EmbeddingResponse response = selection.service().embed(List.of(input), EmbeddingPurpose.QUERY);
		record(selection.provider(), selection.model(), QUERY_FEATURE, repositoryId, snapshotId, response, List.of(input),
				(System.nanoTime() - started) / 1_000_000);
		router.report(selection.provider(), response);
		if (!response.isOk() || response.vectors().isEmpty()) {
			return new QueryVector(null, "the query could not be embedded (" + response.detail() + ")");
		}
		return new QueryVector(response.vectors().get(0), null);
	}

	private void record(String provider, String model, String feature, UUID repositoryId, UUID snapshotId,
			EmbeddingResponse response, List<String> texts, long latency) {
		try {
			int chars = texts.stream().mapToInt(String::length).sum();
			usage.record(new AIUsageService.Call(provider, model, feature, VERSION_LABEL, repositoryId, snapshotId, null,
					response.status(), false, response.inputTokens(), null, (int) Math.ceil(chars / 4.0), null, latency,
					response.detail()));
		}
		catch (RuntimeException ex) {
			log.warn("Could not record embedding usage: {}", ex.getClass().getSimpleName());
		}
	}

}
