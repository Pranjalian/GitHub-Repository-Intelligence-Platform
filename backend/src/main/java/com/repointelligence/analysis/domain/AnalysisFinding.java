package com.repointelligence.analysis.domain;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
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

@Entity
@Table(name = "analysis_finding")
public class AnalysisFinding {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "analyzer_id", nullable = false)
	private String analyzerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false)
	private AnalysisCategory category;

	@Column(name = "rule_id", nullable = false)
	private String ruleId;

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false)
	private Severity severity;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence", nullable = false)
	private Confidence confidence;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "file_path")
	private String filePath;

	@Column(name = "module_path")
	private String modulePath;

	@Column(name = "start_line")
	private Integer startLine;

	@Column(name = "end_line")
	private Integer endLine;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private Map<String, Object> evidence;

	@Enumerated(EnumType.STRING)
	@Column(name = "analysis_method", nullable = false)
	private AnalysisMethod analysisMethod;

	@Column(name = "fingerprint", nullable = false)
	private String fingerprint;

	@Column(name = "suggested_next_step", columnDefinition = "text")
	private String suggestedNextStep;

	protected AnalysisFinding() {
	}

	public AnalysisFinding(RepositorySnapshot snapshot, AnalysisRun run, String analyzerId, AnalysisCategory category, String ruleId, Severity severity, Confidence confidence, String title, AnalysisMethod analysisMethod, String fingerprint) {
		this.snapshot = snapshot;
		this.run = run;
		this.analyzerId = analyzerId;
		this.category = category;
		this.ruleId = ruleId;
		this.severity = severity;
		this.confidence = confidence;
		this.title = title;
		this.analysisMethod = analysisMethod;
		this.fingerprint = fingerprint;
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

	public String getAnalyzerId() {
		return analyzerId;
	}

	public void setAnalyzerId(String analyzerId) {
		this.analyzerId = analyzerId;
	}

	public AnalysisCategory getCategory() {
		return category;
	}

	public void setCategory(AnalysisCategory category) {
		this.category = category;
	}

	public String getRuleId() {
		return ruleId;
	}

	public void setRuleId(String ruleId) {
		this.ruleId = ruleId;
	}

	public Severity getSeverity() {
		return severity;
	}

	public void setSeverity(Severity severity) {
		this.severity = severity;
	}

	public Confidence getConfidence() {
		return confidence;
	}

	public void setConfidence(Confidence confidence) {
		this.confidence = confidence;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public String getModulePath() {
		return modulePath;
	}

	public void setModulePath(String modulePath) {
		this.modulePath = modulePath;
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

	public Map<String, Object> getEvidence() {
		return evidence;
	}

	public void setEvidence(Map<String, Object> evidence) {
		this.evidence = evidence;
	}

	public AnalysisMethod getAnalysisMethod() {
		return analysisMethod;
	}

	public void setAnalysisMethod(AnalysisMethod analysisMethod) {
		this.analysisMethod = analysisMethod;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public void setFingerprint(String fingerprint) {
		this.fingerprint = fingerprint;
	}

	public String getSuggestedNextStep() {
		return suggestedNextStep;
	}

	public void setSuggestedNextStep(String suggestedNextStep) {
		this.suggestedNextStep = suggestedNextStep;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AnalysisFinding other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
