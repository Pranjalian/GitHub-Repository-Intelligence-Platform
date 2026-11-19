package com.repointelligence.analysis.domain;

import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.domain.RepositorySnapshot;
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

@Entity
@Table(name = "analysis_run")
public class AnalysisRun {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private RunStatus status;

	@Column(name = "force_rescan", nullable = false)
	private boolean forceRescan;

	@Column(name = "analyzer_set_hash")
	private String analyzerSetHash;

	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	@Column(name = "retry_after")
	private Instant retryAfter;

	@CreationTimestamp
	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected AnalysisRun() {
	}

	public AnalysisRun(RepositorySnapshot snapshot, RunStatus status) {
		this.snapshot = snapshot;
		this.status = status;
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

	public RunStatus getStatus() {
		return status;
	}

	public void setStatus(RunStatus status) {
		this.status = status;
	}

	public boolean isForceRescan() {
		return forceRescan;
	}

	public void setForceRescan(boolean forceRescan) {
		this.forceRescan = forceRescan;
	}

	public String getAnalyzerSetHash() {
		return analyzerSetHash;
	}

	public void setAnalyzerSetHash(String analyzerSetHash) {
		this.analyzerSetHash = analyzerSetHash;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public void setFailureReason(String failureReason) {
		this.failureReason = failureReason;
	}

	public Instant getRetryAfter() {
		return retryAfter;
	}

	public void setRetryAfter(Instant retryAfter) {
		this.retryAfter = retryAfter;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AnalysisRun other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
