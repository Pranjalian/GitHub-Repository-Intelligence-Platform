package com.repointelligence.architecture.domain;

import com.repointelligence.analysis.domain.AnalysisRun;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A design pattern proposed from structural evidence. An inferred description, POSSIBLE unless strongly supported. */
@Entity
@Table(name = "pattern_candidate")
public class PatternCandidate {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Enumerated(EnumType.STRING)
	@Column(name = "pattern", nullable = false)
	private PatternType pattern;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "symbol_ids", nullable = false)
	private List<UUID> symbolIds;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence", nullable = false)
	private Confidence confidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private Map<String, Object> evidence;

	protected PatternCandidate() {
	}

	public PatternCandidate(RepositorySnapshot snapshot, AnalysisRun run, PatternType pattern, List<UUID> symbolIds, Confidence confidence) {
		this.snapshot = snapshot;
		this.run = run;
		this.pattern = pattern;
		this.symbolIds = symbolIds;
		this.confidence = confidence;
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

	public PatternType getPattern() {
		return pattern;
	}

	public List<UUID> getSymbolIds() {
		return symbolIds;
	}

	public Confidence getConfidence() {
		return confidence;
	}

	public Map<String, Object> getEvidence() {
		return evidence;
	}

	public void setEvidence(Map<String, Object> evidence) {
		this.evidence = evidence;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PatternCandidate other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
