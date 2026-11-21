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

/** A JPA association or a repository-to-entity mapping, read from annotations and generic arguments. A raw parser fact. */
@Entity
@Table(name = "entity_relationship")
public class EntityRelationship {

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
	private EntityRelationshipType relationshipType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "target_symbol_id")
	private CodeSymbol target;

	@Column(name = "target_unresolved_name")
	private String targetUnresolvedName;

	@Column(name = "field_name")
	private String fieldName;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "attributes")
	private Map<String, String> attributes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private Map<String, Object> evidence;

	protected EntityRelationship() {
	}

	public EntityRelationship(RepositorySnapshot snapshot, AnalysisRun run, CodeSymbol source, EntityRelationshipType relationshipType) {
		this.snapshot = snapshot;
		this.run = run;
		this.source = source;
		this.relationshipType = relationshipType;
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

	public EntityRelationshipType getRelationshipType() {
		return relationshipType;
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

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}

	public void setAttributes(Map<String, String> attributes) {
		this.attributes = attributes;
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
		if (!(o instanceof EntityRelationship other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
