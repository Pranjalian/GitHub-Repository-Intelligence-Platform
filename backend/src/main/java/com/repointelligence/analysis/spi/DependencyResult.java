package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import java.util.List;
import java.util.Objects;

public record DependencyResult(Ecosystem ecosystem, String manifestPath, String modulePath, String namespace,
		String name, String declaredVersion, String resolvedVersion, VersionSource versionSource, String scope,
		boolean direct, boolean managementEntry, VulnerabilityStatus vulnerabilityStatus,
		List<String> vulnerabilityIds, String note) {

	/** Without a note. */
	public DependencyResult(Ecosystem ecosystem, String manifestPath, String modulePath, String namespace,
			String name, String declaredVersion, String resolvedVersion, VersionSource versionSource, String scope,
			boolean direct, boolean managementEntry, VulnerabilityStatus vulnerabilityStatus,
			List<String> vulnerabilityIds) {
		this(ecosystem, manifestPath, modulePath, namespace, name, declaredVersion, resolvedVersion, versionSource,
				scope, direct, managementEntry, vulnerabilityStatus, vulnerabilityIds, null);
	}

	public DependencyResult {
		Objects.requireNonNull(ecosystem, "ecosystem");
		Objects.requireNonNull(manifestPath, "manifestPath");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(versionSource, "versionSource");
		vulnerabilityStatus = vulnerabilityStatus == null ? VulnerabilityStatus.NOT_CHECKED : vulnerabilityStatus;
		vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
	}

}
