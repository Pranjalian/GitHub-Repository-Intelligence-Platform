package com.repointelligence.repository.domain;

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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "file_record")
public class FileRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@Column(name = "path", nullable = false)
	private String path;

	@Column(name = "language")
	private String language;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_type", nullable = false)
	private FileType fileType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "content_hash", length = 64)
	private String contentHash;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "git_blob_sha", length = 40)
	private String gitBlobSha;

	@Column(name = "is_binary", nullable = false)
	private boolean binary;

	@Column(name = "skipped_reason")
	private String skippedReason;

	protected FileRecord() {
	}

	public FileRecord(RepositorySnapshot snapshot, String path, FileType fileType, long sizeBytes) {
		this.snapshot = snapshot;
		this.path = path;
		this.fileType = fileType;
		this.sizeBytes = sizeBytes;
	}

	public UUID getId() {
		return id;
	}

	public RepositorySnapshot getSnapshot() {
		return snapshot;
	}

	public void setSnapshot(RepositorySnapshot snapshot) {
		this.snapshot = snapshot;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	public FileType getFileType() {
		return fileType;
	}

	public void setFileType(FileType fileType) {
		this.fileType = fileType;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public void setSizeBytes(long sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public String getGitBlobSha() {
		return gitBlobSha;
	}

	public void setGitBlobSha(String gitBlobSha) {
		this.gitBlobSha = gitBlobSha;
	}

	public boolean isBinary() {
		return binary;
	}

	public void setBinary(boolean binary) {
		this.binary = binary;
	}

	public String getSkippedReason() {
		return skippedReason;
	}

	public void setSkippedReason(String skippedReason) {
		this.skippedReason = skippedReason;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FileRecord other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
