package com.repointelligence.analysis.domain;

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
@Table(name = "dependency")
public class DependencyRecord {

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
	@Column(name = "ecosystem", nullable = false)
	private Ecosystem ecosystem;

	@Column(name = "manifest_path", nullable = false)
	private String manifestPath;

	@Column(name = "module_path")
	private String modulePath;

	@Column(name = "namespace")
	private String namespace;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "declared_version")
	private String declaredVersion;

	@Column(name = "resolved_version")
	private String resolvedVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "version_source", nullable = false)
	private VersionSource versionSource;

	@Column(name = "scope")
	private String scope;

	@Column(name = "is_direct", nullable = false)
	private boolean direct = true;

	@Column(name = "is_management_entry", nullable = false)
	private boolean managementEntry;

	@Enumerated(EnumType.STRING)
	@Column(name = "vulnerability_status", nullable = false)
	private VulnerabilityStatus vulnerabilityStatus = VulnerabilityStatus.NOT_CHECKED;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "vulnerability_ids")
	private List<String> vulnerabilityIds;

	protected DependencyRecord() {
	}

	public DependencyRecord(RepositorySnapshot snapshot, AnalysisRun run, String analyzerId, Ecosystem ecosystem, String manifestPath, String name, VersionSource versionSource) {
		this.snapshot = snapshot;
		this.run = run;
		this.analyzerId = analyzerId;
		this.ecosystem = ecosystem;
		this.manifestPath = manifestPath;
		this.name = name;
		this.versionSource = versionSource;
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

	public Ecosystem getEcosystem() {
		return ecosystem;
	}

	public void setEcosystem(Ecosystem ecosystem) {
		this.ecosystem = ecosystem;
	}

	public String getManifestPath() {
		return manifestPath;
	}

	public void setManifestPath(String manifestPath) {
		this.manifestPath = manifestPath;
	}

	public String getModulePath() {
		return modulePath;
	}

	public void setModulePath(String modulePath) {
		this.modulePath = modulePath;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDeclaredVersion() {
		return declaredVersion;
	}

	public void setDeclaredVersion(String declaredVersion) {
		this.declaredVersion = declaredVersion;
	}

	public String getResolvedVersion() {
		return resolvedVersion;
	}

	public void setResolvedVersion(String resolvedVersion) {
		this.resolvedVersion = resolvedVersion;
	}

	public VersionSource getVersionSource() {
		return versionSource;
	}

	public void setVersionSource(VersionSource versionSource) {
		this.versionSource = versionSource;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public boolean isDirect() {
		return direct;
	}

	public void setDirect(boolean direct) {
		this.direct = direct;
	}

	public boolean isManagementEntry() {
		return managementEntry;
	}

	public void setManagementEntry(boolean managementEntry) {
		this.managementEntry = managementEntry;
	}

	public VulnerabilityStatus getVulnerabilityStatus() {
		return vulnerabilityStatus;
	}

	public void setVulnerabilityStatus(VulnerabilityStatus vulnerabilityStatus) {
		this.vulnerabilityStatus = vulnerabilityStatus;
	}

	public List<String> getVulnerabilityIds() {
		return vulnerabilityIds;
	}

	public void setVulnerabilityIds(List<String> vulnerabilityIds) {
		this.vulnerabilityIds = vulnerabilityIds;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DependencyRecord other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
