package com.repointelligence.analysis.domain;

import com.repointelligence.common.model.Confidence;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "technology_finding")
public class TechnologyFinding {

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
	@Column(name = "kind", nullable = false)
	private TechnologyKind kind;

	@Column(name = "version")
	private String version;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence", nullable = false)
	private Confidence confidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private List<Evidence> evidence;

	@Column(name = "detector_id", nullable = false)
	private String detectorId;

	@Column(name = "share_percent", precision = 5, scale = 2)
	private BigDecimal sharePercent;

	protected TechnologyFinding() {
	}

	public TechnologyFinding(RepositorySnapshot snapshot, AnalysisRun run, String technology, TechnologyKind kind, Confidence confidence, String detectorId) {
		this.snapshot = snapshot;
		this.run = run;
		this.technology = technology;
		this.kind = kind;
		this.confidence = confidence;
		this.detectorId = detectorId;
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

	public TechnologyKind getKind() {
		return kind;
	}

	public void setKind(TechnologyKind kind) {
		this.kind = kind;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public Confidence getConfidence() {
		return confidence;
	}

	public void setConfidence(Confidence confidence) {
		this.confidence = confidence;
	}

	public List<Evidence> getEvidence() {
		return evidence;
	}

	public void setEvidence(List<Evidence> evidence) {
		this.evidence = evidence;
	}

	public String getDetectorId() {
		return detectorId;
	}

	public void setDetectorId(String detectorId) {
		this.detectorId = detectorId;
	}

	public BigDecimal getSharePercent() {
		return sharePercent;
	}

	public void setSharePercent(BigDecimal sharePercent) {
		this.sharePercent = sharePercent;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof TechnologyFinding other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
