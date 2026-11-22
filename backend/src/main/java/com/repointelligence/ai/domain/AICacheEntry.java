package com.repointelligence.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A validated answer, reused for the same provider, model, prompt version, commit and prompt. */
@Entity
@Table(name = "ai_cache")
public class AICacheEntry {

	@Id
	@Column(name = "cache_key")
	private String cacheKey;

	@Column(name = "provider", nullable = false)
	private String provider;

	@Column(name = "model")
	private String model;

	@Column(name = "prompt_version", nullable = false)
	private String promptVersion;

	@Column(name = "feature", nullable = false)
	private String feature;

	@Column(name = "commit_sha", nullable = false)
	private String commitSha;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "structured")
	private Object structured;

	@Column(name = "answered_model")
	private String answeredModel;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected AICacheEntry() {
	}

	public AICacheEntry(String cacheKey, String provider, String model, String promptVersion, String feature,
			String commitSha, String content, Object structured, String answeredModel, Instant createdAt,
			Instant expiresAt) {
		this.cacheKey = cacheKey;
		this.provider = provider;
		this.model = model;
		this.promptVersion = promptVersion;
		this.feature = feature;
		this.commitSha = commitSha;
		this.content = content;
		this.structured = structured;
		this.answeredModel = answeredModel;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public String getCacheKey() {
		return cacheKey;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public String getFeature() {
		return feature;
	}

	public String getCommitSha() {
		return commitSha;
	}

	public String getContent() {
		return content;
	}

	public Object getStructured() {
		return structured;
	}

	public String getAnsweredModel() {
		return answeredModel;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
