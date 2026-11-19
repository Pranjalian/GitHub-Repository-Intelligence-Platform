package com.repointelligence.repository.domain;

import com.repointelligence.common.model.IngestionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "repository_snapshot")
public class RepositorySnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "repository_id", nullable = false)
	private TrackedRepository repository;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "commit_sha", length = 40, nullable = false)
	private String commitSha;

	@Column(name = "branch")
	private String branch;

	@Enumerated(EnumType.STRING)
	@Column(name = "ingestion_status", nullable = false)
	private IngestionStatus ingestionStatus = IngestionStatus.PENDING;

	@Column(name = "ingestion_error", columnDefinition = "text")
	private String ingestionError;

	@Column(name = "file_count")
	private Integer fileCount;

	@Column(name = "total_bytes")
	private Long totalBytes;

	@Column(name = "ingested_at")
	private Instant ingestedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected RepositorySnapshot() {
	}

	public RepositorySnapshot(TrackedRepository repository, String commitSha) {
		this.repository = repository;
		this.commitSha = commitSha;
	}

	public UUID getId() {
		return id;
	}

	public TrackedRepository getRepository() {
		return repository;
	}

	public void setRepository(TrackedRepository repository) {
		this.repository = repository;
	}

	public String getCommitSha() {
		return commitSha;
	}

	public void setCommitSha(String commitSha) {
		this.commitSha = commitSha;
	}

	public String getBranch() {
		return branch;
	}

	public void setBranch(String branch) {
		this.branch = branch;
	}

	public IngestionStatus getIngestionStatus() {
		return ingestionStatus;
	}

	public void setIngestionStatus(IngestionStatus ingestionStatus) {
		this.ingestionStatus = ingestionStatus;
	}

	public String getIngestionError() {
		return ingestionError;
	}

	public void setIngestionError(String ingestionError) {
		this.ingestionError = ingestionError;
	}

	public Integer getFileCount() {
		return fileCount;
	}

	public void setFileCount(Integer fileCount) {
		this.fileCount = fileCount;
	}

	public Long getTotalBytes() {
		return totalBytes;
	}

	public void setTotalBytes(Long totalBytes) {
		this.totalBytes = totalBytes;
	}

	public Instant getIngestedAt() {
		return ingestedAt;
	}

	public void setIngestedAt(Instant ingestedAt) {
		this.ingestedAt = ingestedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RepositorySnapshot other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
