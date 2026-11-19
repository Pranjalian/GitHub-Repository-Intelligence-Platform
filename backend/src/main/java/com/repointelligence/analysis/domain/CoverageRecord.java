package com.repointelligence.analysis.domain;

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
@Table(name = "coverage_record")
public class CoverageRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "report_path", nullable = false)
	private String reportPath;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false)
	private CoverageScope scope;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "line_covered")
	private Integer lineCovered;

	@Column(name = "line_missed")
	private Integer lineMissed;

	@Column(name = "branch_covered")
	private Integer branchCovered;

	@Column(name = "branch_missed")
	private Integer branchMissed;

	protected CoverageRecord() {
	}

	public CoverageRecord(RepositorySnapshot snapshot, AnalysisRun run, String reportPath, CoverageScope scope, String name) {
		this.snapshot = snapshot;
		this.run = run;
		this.reportPath = reportPath;
		this.scope = scope;
		this.name = name;
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

	public String getReportPath() {
		return reportPath;
	}

	public void setReportPath(String reportPath) {
		this.reportPath = reportPath;
	}

	public CoverageScope getScope() {
		return scope;
	}

	public void setScope(CoverageScope scope) {
		this.scope = scope;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Integer getLineCovered() {
		return lineCovered;
	}

	public void setLineCovered(Integer lineCovered) {
		this.lineCovered = lineCovered;
	}

	public Integer getLineMissed() {
		return lineMissed;
	}

	public void setLineMissed(Integer lineMissed) {
		this.lineMissed = lineMissed;
	}

	public Integer getBranchCovered() {
		return branchCovered;
	}

	public void setBranchCovered(Integer branchCovered) {
		this.branchCovered = branchCovered;
	}

	public Integer getBranchMissed() {
		return branchMissed;
	}

	public void setBranchMissed(Integer branchMissed) {
		this.branchMissed = branchMissed;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CoverageRecord other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
