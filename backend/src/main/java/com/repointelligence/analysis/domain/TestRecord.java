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
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "test_record")
public class TestRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "test_file_path", nullable = false)
	private String testFilePath;

	@Column(name = "test_symbol")
	private String testSymbol;

	@Column(name = "test_method_count")
	private Integer testMethodCount;

	@Column(name = "framework")
	private String framework;

	@Column(name = "production_symbol")
	private String productionSymbol;

	@Enumerated(EnumType.STRING)
	@Column(name = "mapping_method")
	private MappingMethod mappingMethod;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence")
	private Confidence confidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private List<Evidence> evidence;

	protected TestRecord() {
	}

	public TestRecord(RepositorySnapshot snapshot, AnalysisRun run, String testFilePath) {
		this.snapshot = snapshot;
		this.run = run;
		this.testFilePath = testFilePath;
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

	public String getTestFilePath() {
		return testFilePath;
	}

	public void setTestFilePath(String testFilePath) {
		this.testFilePath = testFilePath;
	}

	public String getTestSymbol() {
		return testSymbol;
	}

	public void setTestSymbol(String testSymbol) {
		this.testSymbol = testSymbol;
	}

	public Integer getTestMethodCount() {
		return testMethodCount;
	}

	public void setTestMethodCount(Integer testMethodCount) {
		this.testMethodCount = testMethodCount;
	}

	public String getFramework() {
		return framework;
	}

	public void setFramework(String framework) {
		this.framework = framework;
	}

	public String getProductionSymbol() {
		return productionSymbol;
	}

	public void setProductionSymbol(String productionSymbol) {
		this.productionSymbol = productionSymbol;
	}

	public MappingMethod getMappingMethod() {
		return mappingMethod;
	}

	public void setMappingMethod(MappingMethod mappingMethod) {
		this.mappingMethod = mappingMethod;
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

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof TestRecord other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
