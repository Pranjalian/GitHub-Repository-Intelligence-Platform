package com.repointelligence.analysis.domain;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analyzer_execution")
public class AnalyzerExecution {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@Column(name = "analyzer_id", nullable = false)
	private String analyzerId;

	@Column(name = "analyzer_version", nullable = false)
	private String analyzerVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false)
	private AnalysisCategory category;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ExecutionStatus status;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "duration_ms")
	private Long durationMs;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config_summary")
	private Map<String, Object> configSummary;

	@Column(name = "error_summary", columnDefinition = "text")
	private String errorSummary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "limitations")
	private List<String> limitations;

	protected AnalyzerExecution() {
	}

	public AnalyzerExecution(AnalysisRun run, RepositorySnapshot snapshot, String analyzerId, String analyzerVersion, AnalysisCategory category, ExecutionStatus status) {
		this.run = run;
		this.snapshot = snapshot;
		this.analyzerId = analyzerId;
		this.analyzerVersion = analyzerVersion;
		this.category = category;
		this.status = status;
	}

	public UUID getId() {
		return id;
	}

	public AnalysisRun getRun() {
		return run;
	}

	public void setRun(AnalysisRun run) {
		this.run = run;
	}

	public RepositorySnapshot getSnapshot() {
		return snapshot;
	}

	public void setSnapshot(RepositorySnapshot snapshot) {
		this.snapshot = snapshot;
	}

	public String getAnalyzerId() {
		return analyzerId;
	}

	public void setAnalyzerId(String analyzerId) {
		this.analyzerId = analyzerId;
	}

	public String getAnalyzerVersion() {
		return analyzerVersion;
	}

	public void setAnalyzerVersion(String analyzerVersion) {
		this.analyzerVersion = analyzerVersion;
	}

	public AnalysisCategory getCategory() {
		return category;
	}

	public void setCategory(AnalysisCategory category) {
		this.category = category;
	}

	public ExecutionStatus getStatus() {
		return status;
	}

	public void setStatus(ExecutionStatus status) {
		this.status = status;
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

	public Long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
	}

	public Map<String, Object> getConfigSummary() {
		return configSummary;
	}

	public void setConfigSummary(Map<String, Object> configSummary) {
		this.configSummary = configSummary;
	}

	public String getErrorSummary() {
		return errorSummary;
	}

	public void setErrorSummary(String errorSummary) {
		this.errorSummary = errorSummary;
	}

	public List<String> getLimitations() {
		return limitations;
	}

	public void setLimitations(List<String> limitations) {
		this.limitations = limitations;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AnalyzerExecution other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
