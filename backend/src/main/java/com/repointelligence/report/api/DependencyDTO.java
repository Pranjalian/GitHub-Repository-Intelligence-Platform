package com.repointelligence.report.api;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import java.util.List;
import java.util.UUID;

public record DependencyDTO(UUID id, String analyzerId, Ecosystem ecosystem, String manifestPath, String module,
		String namespace, String name, String declaredVersion, String resolvedVersion, VersionSource versionSource,
		String scope, boolean direct, boolean managementEntry, VulnerabilityStatus vulnerabilityStatus,
		List<String> vulnerabilityIds, String note) {
}
