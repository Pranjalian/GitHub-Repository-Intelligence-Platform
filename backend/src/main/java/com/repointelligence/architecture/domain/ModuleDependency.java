package com.repointelligence.architecture.domain;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.repository.domain.RepositorySnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A dependency of one build module on another module of the same snapshot. */
@Entity
@Table(name = "module_dependency")
public class ModuleDependency {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "source_module_path", nullable = false)
	private String sourceModulePath;

	@Column(name = "target_module_path", nullable = false)
	private String targetModulePath;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private List<Evidence> evidence;

	protected ModuleDependency() {
	}

	public ModuleDependency(RepositorySnapshot snapshot, AnalysisRun run, String sourceModulePath, String targetModulePath) {
		this.snapshot = snapshot;
		this.run = run;
		this.sourceModulePath = sourceModulePath;
		this.targetModulePath = targetModulePath;
	}

	public UUID getId() {
		return id;
	}

	public RepositorySnapshot getSnapshot() {
		return snapshot;
	}

	public AnalysisRun getRun() {
		return run;
	}

	public String getSourceModulePath() {
		return sourceModulePath;
	}

	public String getTargetModulePath() {
		return targetModulePath;
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
		if (!(o instanceof ModuleDependency other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
