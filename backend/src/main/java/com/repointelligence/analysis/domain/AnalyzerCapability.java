package com.repointelligence.analysis.domain;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
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
import java.util.UUID;

@Entity
@Table(name = "analyzer_capability")
public class AnalyzerCapability {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "technology", nullable = false)
	private String technology;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false)
	private AnalysisCategory category;

	@Column(name = "analyzer_id")
	private String analyzerId;

	@Column(name = "analyzer_version")
	private String analyzerVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "capability_level", nullable = false)
	private CapabilityLevel capabilityLevel;

	@Enumerated(EnumType.STRING)
	@Column(name = "execution_status", nullable = false)
	private ExecutionStatus executionStatus;

	@Column(name = "limitation_reason", columnDefinition = "text")
	private String limitationReason;

	protected AnalyzerCapability() {
	}

	public AnalyzerCapability(RepositorySnapshot snapshot, AnalysisRun run, String technology, AnalysisCategory category, CapabilityLevel capabilityLevel, ExecutionStatus executionStatus) {
		this.snapshot = snapshot;
		this.run = run;
		this.technology = technology;
		this.category = category;
		this.capabilityLevel = capabilityLevel;
		this.executionStatus = executionStatus;
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

	public AnalysisRun getRun() {
		return run;
	}

	public void setRun(AnalysisRun run) {
		this.run = run;
	}

	public String getTechnology() {
		return technology;
	}

	public void setTechnology(String technology) {
		this.technology = technology;
	}

	public AnalysisCategory getCategory() {
		return category;
	}

	public void setCategory(AnalysisCategory category) {
		this.category = category;
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

	public CapabilityLevel getCapabilityLevel() {
		return capabilityLevel;
	}

	public void setCapabilityLevel(CapabilityLevel capabilityLevel) {
		this.capabilityLevel = capabilityLevel;
	}

	public ExecutionStatus getExecutionStatus() {
		return executionStatus;
	}

	public void setExecutionStatus(ExecutionStatus executionStatus) {
		this.executionStatus = executionStatus;
	}

	public String getLimitationReason() {
		return limitationReason;
	}

	public void setLimitationReason(String limitationReason) {
		this.limitationReason = limitationReason;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AnalyzerCapability other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
