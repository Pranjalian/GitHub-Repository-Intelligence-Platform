package com.repointelligence.rag.qa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A Q&A conversation about one snapshot of one repository. */
@Entity
@Table(name = "conversation")
public class Conversation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "repository_id", nullable = false, updatable = false)
	private UUID repositoryId;

	@Column(name = "snapshot_id", nullable = false, updatable = false)
	private UUID snapshotId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Conversation() {
	}

	public Conversation(UUID repositoryId, UUID snapshotId, String title, Instant at) {
		this.repositoryId = repositoryId;
		this.snapshotId = snapshotId;
		this.title = title.length() <= 200 ? title : title.substring(0, 197) + "...";
		this.createdAt = at;
		this.updatedAt = at;
	}

	public void touch(Instant at) {
		this.updatedAt = at;
	}

	public UUID getId() {
		return id;
	}

	public UUID getRepositoryId() {
		return repositoryId;
	}

	public UUID getSnapshotId() {
		return snapshotId;
	}

	public String getTitle() {
		return title;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
