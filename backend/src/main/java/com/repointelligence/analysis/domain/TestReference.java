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
import java.util.UUID;

/** A raw fact: a test class names a production type, or calls one of its methods (MVP 5). */
@Entity
@Table(name = "test_reference")
public class TestReference {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "test_symbol", nullable = false)
	private String testSymbol;

	@Column(name = "test_file_path", nullable = false)
	private String testFilePath;

	@Column(name = "test_method")
	private String testMethod;

	@Column(name = "referenced_type", nullable = false)
	private String referencedType;

	@Column(name = "referenced_member")
	private String referencedMember;

	@Column(name = "argument_count")
	private Integer argumentCount;

	@Column(name = "reference_kind", nullable = false)
	private String referenceKind;

	@Column(name = "line_number")
	private Integer lineNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence", nullable = false)
	private Confidence confidence;

	protected TestReference() {
	}

	public TestReference(RepositorySnapshot snapshot, AnalysisRun run, String testSymbol, String testFilePath,
			String testMethod, String referencedType, String referencedMember, Integer argumentCount,
			String referenceKind, Integer lineNumber, Confidence confidence) {
		this.snapshot = snapshot;
		this.run = run;
		this.testSymbol = testSymbol;
		this.testFilePath = testFilePath;
		this.testMethod = testMethod;
		this.referencedType = referencedType;
		this.referencedMember = referencedMember;
		this.argumentCount = argumentCount;
		this.referenceKind = referenceKind;
		this.lineNumber = lineNumber;
		this.confidence = confidence;
	}

	public UUID getId() {
		return id;
	}

	public String getTestSymbol() {
		return testSymbol;
	}

	public String getReferencedType() {
		return referencedType;
	}

	public String getReferencedMember() {
		return referencedMember;
	}

}
