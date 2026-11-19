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
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "module_record")
public class ModuleRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "module_name", nullable = false)
	private String moduleName;

	@Column(name = "path", nullable = false)
	private String path;

	@Column(name = "build_file_path")
	private String buildFilePath;

	@Enumerated(EnumType.STRING)
	@Column(name = "ecosystem")
	private Ecosystem ecosystem;

	@Column(name = "detected_responsibility")
	private String detectedResponsibility;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private List<Evidence> evidence;

	protected ModuleRecord() {
	}

	public ModuleRecord(RepositorySnapshot snapshot, AnalysisRun run, String moduleName, String path) {
		this.snapshot = snapshot;
		this.run = run;
		this.moduleName = moduleName;
		this.path = path;
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

	public String getModuleName() {
		return moduleName;
	}

	public void setModuleName(String moduleName) {
		this.moduleName = moduleName;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getBuildFilePath() {
		return buildFilePath;
	}

	public void setBuildFilePath(String buildFilePath) {
		this.buildFilePath = buildFilePath;
	}

	public Ecosystem getEcosystem() {
		return ecosystem;
	}

	public void setEcosystem(Ecosystem ecosystem) {
		this.ecosystem = ecosystem;
	}

	public String getDetectedResponsibility() {
		return detectedResponsibility;
	}

	public void setDetectedResponsibility(String detectedResponsibility) {
		this.detectedResponsibility = detectedResponsibility;
	}

	public List<Evidence> getEvidence() {
		return evidence;
	}

	public void setEvidence(List<Evidence> evidence) {
		this.evidence = evidence;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ModuleRecord other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
