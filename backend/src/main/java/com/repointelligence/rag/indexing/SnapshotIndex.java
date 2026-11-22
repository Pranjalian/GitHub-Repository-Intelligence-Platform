package com.repointelligence.rag.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The index job of one snapshot and its outcome. Re-indexing reuses the row. */
@Entity
@Table(name = "snapshot_index")
public class SnapshotIndex {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "snapshot_id", nullable = false, updatable = false)
	private UUID snapshotId;

	@Column(name = "repository_id", nullable = false, updatable = false)
	private UUID repositoryId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private IndexStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "embedding_status", nullable = false)
	private EmbeddingStatus embeddingStatus;

	@Column(name = "embedding_detail")
	private String embeddingDetail;

	@Column(name = "embedding_provider")
	private String embeddingProvider;

	@Column(name = "embedding_model")
	private String embeddingModel;

	@Column(name = "embedding_model_version")
	private String embeddingModelVersion;

	@Column(name = "embedding_dimensions")
	private Integer embeddingDimensions;

	@Column(name = "files_indexed")
	private Integer filesIndexed;

	@Column(name = "files_skipped")
	private Integer filesSkipped;

	@Column(name = "chunk_count")
	private Integer chunkCount;

	@Column(name = "embedded_chunks")
	private Integer embeddedChunks;

	@Column(name = "reused_embeddings")
	private Integer reusedEmbeddings;

	@Column(name = "new_embeddings")
	private Integer newEmbeddings;

	@Column(name = "detail")
	private String detail;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "warnings")
	private List<String> warnings;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected SnapshotIndex() {
	}

	public SnapshotIndex(UUID snapshotId, UUID repositoryId, Instant requestedAt) {
		this.snapshotId = snapshotId;
		this.repositoryId = repositoryId;
		queue(requestedAt);
	}

	/** Resets the row for a new job. */
	public final void queue(Instant at) {
		this.status = IndexStatus.QUEUED;
		this.embeddingStatus = EmbeddingStatus.PENDING;
		this.requestedAt = at;
		this.startedAt = null;
		this.completedAt = null;
		this.detail = null;
		this.warnings = null;
	}

	public void start(Instant at) {
		this.status = IndexStatus.INDEXING;
		this.startedAt = at;
	}

	public void complete(IndexStatus outcome, String newDetail, List<String> newWarnings, Instant at) {
		this.status = outcome;
		this.detail = truncate(newDetail);
		this.warnings = newWarnings == null || newWarnings.isEmpty() ? null : List.copyOf(newWarnings);
		this.completedAt = at;
	}

	public void counts(int indexed, int skipped, int chunks) {
		this.filesIndexed = indexed;
		this.filesSkipped = skipped;
		this.chunkCount = chunks;
	}

	public void embeddings(EmbeddingStatus newStatus, String newDetail, String provider, String model, String version,
			Integer dimensions, Integer embedded, Integer reused, Integer created) {
		this.embeddingStatus = newStatus;
		this.embeddingDetail = truncate(newDetail);
		this.embeddingProvider = provider;
		this.embeddingModel = model;
		this.embeddingModelVersion = version;
		this.embeddingDimensions = dimensions;
		this.embeddedChunks = embedded;
		this.reusedEmbeddings = reused;
		this.newEmbeddings = created;
	}

	private static String truncate(String value) {
		return value == null || value.length() <= 1000 ? value : value.substring(0, 997) + "...";
	}

	public UUID getId() {
		return id;
	}

	public UUID getSnapshotId() {
		return snapshotId;
	}

	public UUID getRepositoryId() {
		return repositoryId;
	}

	public IndexStatus getStatus() {
		return status;
	}

	public EmbeddingStatus getEmbeddingStatus() {
		return embeddingStatus;
	}

	public String getEmbeddingDetail() {
		return embeddingDetail;
	}

	public String getEmbeddingProvider() {
		return embeddingProvider;
	}

	public String getEmbeddingModel() {
		return embeddingModel;
	}

	public String getEmbeddingModelVersion() {
		return embeddingModelVersion;
	}

	public Integer getEmbeddingDimensions() {
		return embeddingDimensions;
	}

	public Integer getFilesIndexed() {
		return filesIndexed;
	}

	public Integer getFilesSkipped() {
		return filesSkipped;
	}

	public Integer getChunkCount() {
		return chunkCount;
	}

	public Integer getEmbeddedChunks() {
		return embeddedChunks;
	}

	public Integer getReusedEmbeddings() {
		return reusedEmbeddings;
	}

	public Integer getNewEmbeddings() {
		return newEmbeddings;
	}

	public String getDetail() {
		return detail;
	}

	public List<String> getWarnings() {
		return warnings == null ? List.of() : warnings;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

}
