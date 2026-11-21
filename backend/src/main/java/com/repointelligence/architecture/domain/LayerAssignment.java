package com.repointelligence.architecture.domain;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.Evidence;
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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The architectural layer a type was assigned to, with how and how confidently. An inferred description. */
@Entity
@Table(name = "layer_assignment")
public class LayerAssignment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "symbol_id", nullable = false)
	private CodeSymbol symbol;

	@Enumerated(EnumType.STRING)
	@Column(name = "layer", nullable = false)
	private ArchitectureLayer layer;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence", nullable = false)
	private Confidence confidence;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false)
	private LayerMethod method;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private List<Evidence> evidence;

	protected LayerAssignment() {
	}

	public LayerAssignment(RepositorySnapshot snapshot, AnalysisRun run, CodeSymbol symbol, ArchitectureLayer layer, Confidence confidence, LayerMethod method) {
		this.snapshot = snapshot;
		this.run = run;
		this.symbol = symbol;
		this.layer = layer;
		this.confidence = confidence;
		this.method = method;
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

	public CodeSymbol getSymbol() {
		return symbol;
	}

	public ArchitectureLayer getLayer() {
		return layer;
	}

	public Confidence getConfidence() {
		return confidence;
	}

	public LayerMethod getMethod() {
		return method;
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
		if (!(o instanceof LayerAssignment other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
