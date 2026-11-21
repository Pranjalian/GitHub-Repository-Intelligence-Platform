package com.repointelligence.architecture.domain;

import com.repointelligence.analysis.domain.AnalysisRun;
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
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A relationship from one symbol to another (or to a name outside the snapshot), as read from the code. A raw parser fact. */
@Entity
@Table(name = "dependency_edge")
public class DependencyEdge {

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
	@JoinColumn(name = "source_symbol_id", nullable = false)
	private CodeSymbol source;

	@Enumerated(EnumType.STRING)
	@Column(name = "relationship_type", nullable = false)
	private RelationshipType relationshipType;

	@Enumerated(EnumType.STRING)
	@Column(name = "resolution", nullable = false)
	private EdgeResolution resolution;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "target_symbol_id")
	private CodeSymbol target;

	@Column(name = "target_unresolved_name")
	private String targetUnresolvedName;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private Map<String, Object> evidence;

	protected DependencyEdge() {
	}

	public DependencyEdge(RepositorySnapshot snapshot, AnalysisRun run, CodeSymbol source, RelationshipType relationshipType, EdgeResolution resolution) {
		this.snapshot = snapshot;
		this.run = run;
		this.source = source;
		this.relationshipType = relationshipType;
		this.resolution = resolution;
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

	public CodeSymbol getSource() {
		return source;
	}

	public RelationshipType getRelationshipType() {
		return relationshipType;
	}

	public EdgeResolution getResolution() {
		return resolution;
	}

	public CodeSymbol getTarget() {
		return target;
	}

	public void setTarget(CodeSymbol target) {
		this.target = target;
	}

	public String getTargetUnresolvedName() {
		return targetUnresolvedName;
	}

	public void setTargetUnresolvedName(String targetUnresolvedName) {
		this.targetUnresolvedName = targetUnresolvedName;
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
		if (!(o instanceof DependencyEdge other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
