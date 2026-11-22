package com.repointelligence.ai.domain;

import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.UsageSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One provider call or cache hit. Provider-reported token counts are null when the provider reported
 * none; the local estimate is kept separately and {@link #getUsageSource()} says which applies.
 */
@Entity
@Table(name = "ai_usage_record")
public class AIUsageRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "provider", nullable = false)
	private String provider;

	@Column(name = "model")
	private String model;

	@Column(name = "feature", nullable = false)
	private String feature;

	@Column(name = "prompt_version", nullable = false)
	private String promptVersion;

	@Column(name = "repository_id")
	private UUID repositoryId;

	@Column(name = "snapshot_id")
	private UUID snapshotId;

	@Column(name = "run_id")
	private UUID runId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private AIStatus status;

	@Column(name = "cache_hit", nullable = false)
	private boolean cacheHit;

	@Column(name = "input_tokens")
	private Integer inputTokens;

	@Column(name = "output_tokens")
	private Integer outputTokens;

	@Column(name = "estimated_input_tokens")
	private Integer estimatedInputTokens;

	@Column(name = "estimated_output_tokens")
	private Integer estimatedOutputTokens;

	@Enumerated(EnumType.STRING)
	@Column(name = "usage_source", nullable = false)
	private UsageSource usageSource;

	@Column(name = "estimated_cost")
	private BigDecimal estimatedCost;

	@Enumerated(EnumType.STRING)
	@Column(name = "cost_source")
	private CostSource costSource;

	@Column(name = "currency")
	private String currency;

	@Column(name = "latency_ms")
	private Long latencyMs;

	@Column(name = "detail")
	private String detail;

	protected AIUsageRecord() {
	}

	public AIUsageRecord(Instant createdAt, String provider, String model, String feature, String promptVersion,
			AIStatus status, boolean cacheHit, UsageSource usageSource) {
		this.createdAt = createdAt;
		this.provider = provider;
		this.model = model;
		this.feature = feature;
		this.promptVersion = promptVersion;
		this.status = status;
		this.cacheHit = cacheHit;
		this.usageSource = usageSource;
	}

	public UUID getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getFeature() {
		return feature;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public UUID getRepositoryId() {
		return repositoryId;
	}

	public UUID getSnapshotId() {
		return snapshotId;
	}

	public UUID getRunId() {
		return runId;
	}

	public void setScope(UUID repositoryId, UUID snapshotId, UUID runId) {
		this.repositoryId = repositoryId;
		this.snapshotId = snapshotId;
		this.runId = runId;
	}

	public AIStatus getStatus() {
		return status;
	}

	public boolean isCacheHit() {
		return cacheHit;
	}

	public Integer getInputTokens() {
		return inputTokens;
	}

	public Integer getOutputTokens() {
		return outputTokens;
	}

	public void setReportedTokens(Integer input, Integer output) {
		this.inputTokens = input;
		this.outputTokens = output;
	}

	public Integer getEstimatedInputTokens() {
		return estimatedInputTokens;
	}

	public Integer getEstimatedOutputTokens() {
		return estimatedOutputTokens;
	}

	public void setEstimatedTokens(Integer input, Integer output) {
		this.estimatedInputTokens = input;
		this.estimatedOutputTokens = output;
	}

	public UsageSource getUsageSource() {
		return usageSource;
	}

	public BigDecimal getEstimatedCost() {
		return estimatedCost;
	}

	public CostSource getCostSource() {
		return costSource;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCost(BigDecimal cost, CostSource source, String currency) {
		this.estimatedCost = cost;
		this.costSource = source;
		this.currency = currency;
	}

	public Long getLatencyMs() {
		return latencyMs;
	}

	public void setLatencyMs(Long latencyMs) {
		this.latencyMs = latencyMs;
	}

	public String getDetail() {
		return detail;
	}

	public void setDetail(String detail) {
		this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
	}

}
