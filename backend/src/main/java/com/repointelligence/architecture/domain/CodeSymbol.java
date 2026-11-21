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
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A class, interface, enum, record, annotation type, method, constructor or field read from the syntax tree. A raw parser fact. */
@Entity
@Table(name = "code_symbol")
public class CodeSymbol {

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
	@Column(name = "symbol_type", nullable = false)
	private SymbolType symbolType;

	@Column(name = "fully_qualified_name", nullable = false)
	private String fullyQualifiedName;

	@Column(name = "file_path", nullable = false)
	private String filePath;

	@Column(name = "signature")
	private String signature;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_symbol_id")
	private CodeSymbol parent;

	@Column(name = "start_line")
	private Integer startLine;

	@Column(name = "end_line")
	private Integer endLine;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "annotations")
	private List<AnnotationInfo> annotations;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "modifiers")
	private List<String> modifiers;

	@Column(name = "module_path")
	private String modulePath;

	protected CodeSymbol() {
	}

	public CodeSymbol(RepositorySnapshot snapshot, AnalysisRun run, SymbolType symbolType, String fullyQualifiedName, String filePath) {
		this.snapshot = snapshot;
		this.run = run;
		this.symbolType = symbolType;
		this.fullyQualifiedName = fullyQualifiedName;
		this.filePath = filePath;
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

	public SymbolType getSymbolType() {
		return symbolType;
	}

	public String getFullyQualifiedName() {
		return fullyQualifiedName;
	}

	public String getFilePath() {
		return filePath;
	}

	public String getSignature() {
		return signature;
	}

	public void setSignature(String signature) {
		this.signature = signature;
	}

	public CodeSymbol getParent() {
		return parent;
	}

	public void setParent(CodeSymbol parent) {
		this.parent = parent;
	}

	public Integer getStartLine() {
		return startLine;
	}

	public void setStartLine(Integer startLine) {
		this.startLine = startLine;
	}

	public Integer getEndLine() {
		return endLine;
	}

	public void setEndLine(Integer endLine) {
		this.endLine = endLine;
	}

	public List<AnnotationInfo> getAnnotations() {
		return annotations;
	}

	public void setAnnotations(List<AnnotationInfo> annotations) {
		this.annotations = annotations;
	}

	public List<String> getModifiers() {
		return modifiers;
	}

	public void setModifiers(List<String> modifiers) {
		this.modifiers = modifiers;
	}

	public String getModulePath() {
		return modulePath;
	}

	public void setModulePath(String modulePath) {
		this.modulePath = modulePath;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CodeSymbol other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
