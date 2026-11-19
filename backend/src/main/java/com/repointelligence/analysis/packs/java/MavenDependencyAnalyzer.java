package com.repointelligence.analysis.packs.java;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import com.repointelligence.analysis.packs.java.maven.MavenModule;
import com.repointelligence.analysis.packs.java.maven.MavenModule.ResolvedDependency;
import com.repointelligence.analysis.packs.java.maven.MavenPomParser;
import com.repointelligence.analysis.packs.java.maven.MavenReactor;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DependencyAnalyzer;
import com.repointelligence.analysis.spi.DependencyResult;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.repointelligence.analysis.vulnerability.VulnerabilityEnricher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Inventories the direct Maven dependencies and dependency-management entries of every POM in the
 * snapshot, and says plainly what it could not resolve: versions supplied by remote parents or imported
 * BOMs are labelled {@code MANAGED_REMOTE_UNRESOLVED}, and transitive dependencies are not resolved at all.
 */
@Component
public class MavenDependencyAnalyzer implements DependencyAnalyzer {

	public static final String ID = "maven-dependency-analyzer";

	private static final String VERSION = "1.0.0";

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Maven Dependency Analyzer", VERSION, AnalysisCategory.DEPENDENCIES, CapabilityLevel.DEEP)
		.languages("Java")
		.frameworks("Maven")
		.manifests("pom.xml")
		.description("Reads every pom.xml (no network, nothing downloaded) and lists direct dependencies and "
				+ "dependency-management entries with declared and resolved versions and how each version was determined.")
		.limitations("Only direct dependencies are listed; transitive dependencies are not resolved.",
				"Versions managed by remote parents (for example spring-boot-starter-parent) or imported BOMs are "
						+ "not resolved and are labelled as such.",
				"Profile dependencies are listed with their profile id; whether a profile is active is not evaluated.")
		.build();

	private final VulnerabilityEnricher vulnerabilities;

	@Autowired
	public MavenDependencyAnalyzer(VulnerabilityEnricher vulnerabilities) {
		this.vulnerabilities = vulnerabilities;
	}

	/** With vulnerability lookup off. */
	public MavenDependencyAnalyzer() {
		this(VulnerabilityEnricher.disabled());
	}

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		return MavenPomParser.pomPaths(context.files()).isEmpty() ? Applicability.notApplicable()
				: Applicability.applicable();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		MavenReactor reactor = MavenPomParser.parse(context.workspace(), context.files());
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		reactor.warnings().forEach(result::warning);
		List<DependencyResult> inventory = new ArrayList<>();

		int directCount = 0;
		int managementCount = 0;
		Map<String, Map<String, Set<String>>> versionsByArtifact = new LinkedHashMap<>();
		Map<String, String> firstPomByArtifact = new LinkedHashMap<>();
		for (MavenModule module : reactor.modules()) {
			context.cancellation().throwIfCancelled();
			List<ResolvedDependency> dependencies = module.dependencies();
			for (ResolvedDependency dependency : dependencies) {
				inventory.add(toResult(module, dependency));
				directCount++;
				if (dependency.profileId() == null && dependency.resolvedVersion() != null) {
					versionsByArtifact.computeIfAbsent(dependency.coordinate(), k -> new LinkedHashMap<>())
						.computeIfAbsent(dependency.resolvedVersion(), k -> new LinkedHashSet<>())
						.add(module.modulePath());
					firstPomByArtifact.putIfAbsent(dependency.coordinate(), module.pomPath());
				}
			}
			for (ResolvedDependency entry : module.managementEntries()) {
				inventory.add(toResult(module, entry));
				managementCount++;
			}
			duplicateFindings(module, dependencies).forEach(result::finding);
			unresolvedFindings(module, dependencies).forEach(result::finding);
		}
		inconsistencyFindings(versionsByArtifact, firstPomByArtifact).forEach(result::finding);

		VulnerabilityEnricher.Enrichment enriched = vulnerabilities.enrich(inventory);
		enriched.dependencies().forEach(result::dependency);
		enriched.findings().forEach(result::finding);
		enriched.warnings().forEach(result::warning);
		enriched.limitations().forEach(result::limitation);

		result.configSummary(Map.of("pomFiles", reactor.modules().size(), "directDependencies", directCount,
				"managementEntries", managementCount));
		DESCRIPTOR.limitations().forEach(result::limitation);
		return result.build();
	}

	private static DependencyResult toResult(MavenModule module, ResolvedDependency d) {
		return new DependencyResult(Ecosystem.MAVEN, module.pomPath(), module.modulePath(), d.groupId(),
				d.artifactId(), d.declaredVersion(), d.resolvedVersion(), d.versionSource(), d.scope(),
				!d.managementEntry(), d.managementEntry(), VulnerabilityStatus.NOT_CHECKED, List.of(), d.note());
	}

	private static List<FindingResult> duplicateFindings(MavenModule module, List<ResolvedDependency> dependencies) {
		List<FindingResult> findings = new ArrayList<>();
		Map<String, List<ResolvedDependency>> byKey = new LinkedHashMap<>();
		for (ResolvedDependency dependency : dependencies) {
			if (dependency.profileId() == null) {
				byKey.computeIfAbsent(dependency.coordinate() + ":" + dependency.type() + ":" + dependency.classifier(),
						k -> new ArrayList<>())
					.add(dependency);
			}
		}
		for (List<ResolvedDependency> group : byKey.values()) {
			if (group.size() < 2) {
				continue;
			}
			ResolvedDependency second = group.get(1);
			findings.add(new FindingResult(AnalysisCategory.DEPENDENCIES, "maven-duplicate-dependency", Severity.LOW,
					Confidence.DETECTED, "Duplicate dependency declaration: " + second.coordinate(),
					second.coordinate() + " is declared " + group.size() + " times in " + module.pomPath() + ".",
					module.pomPath(), module.modulePath(), second.line(), second.line(),
					Map.of("dependency", second.coordinate(), "declarations", group.size()), AnalysisMethod.PARSER,
					"Keep one declaration and remove the others."));
		}
		return findings;
	}

	private static List<FindingResult> unresolvedFindings(MavenModule module, List<ResolvedDependency> dependencies) {
		List<FindingResult> findings = new ArrayList<>();
		int remoteManaged = 0;
		String remoteNote = null;
		for (ResolvedDependency dependency : dependencies) {
			if (dependency.versionSource() == VersionSource.UNRESOLVED) {
				findings.add(new FindingResult(AnalysisCategory.DEPENDENCIES, "maven-unresolved-version", Severity.INFO,
						Confidence.DETECTED, "Version could not be resolved: " + dependency.coordinate(),
						dependency.note() == null ? "The version of " + dependency.coordinate() + " is unresolved."
								: dependency.note(),
						module.pomPath(), module.modulePath(), dependency.line(), dependency.line(),
						Map.of("dependency", dependency.coordinate(), "declaredVersion",
								String.valueOf(dependency.declaredVersion())),
						AnalysisMethod.PARSER, null));
			}
			else if (dependency.versionSource() == VersionSource.MANAGED_REMOTE_UNRESOLVED) {
				remoteManaged++;
				remoteNote = dependency.note();
			}
		}
		if (remoteManaged > 0) {
			findings.add(new FindingResult(AnalysisCategory.DEPENDENCIES, "maven-remote-managed-versions",
					Severity.INFO, Confidence.DETECTED,
					remoteManaged + " dependency version(s) are managed remotely and not resolved",
					"In " + module.pomPath() + ", " + remoteManaged + " dependencies have no version of their own: "
							+ remoteNote + ". Their versions were not looked up.",
					module.pomPath(), module.modulePath(), null, null,
					Map.of("count", remoteManaged, "managedBy", String.valueOf(remoteNote)), AnalysisMethod.PARSER,
					null));
		}
		return findings;
	}

	private static List<FindingResult> inconsistencyFindings(Map<String, Map<String, Set<String>>> versionsByArtifact,
			Map<String, String> firstPomByArtifact) {
		List<FindingResult> findings = new ArrayList<>();
		versionsByArtifact.forEach((artifact, versions) -> {
			if (versions.size() < 2) {
				return;
			}
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("dependency", artifact);
			evidence.put("versions", versions);
			findings.add(new FindingResult(AnalysisCategory.DEPENDENCIES, "maven-version-inconsistency", Severity.LOW,
					Confidence.DETECTED, "Different versions of " + artifact + " across modules",
					artifact + " is used at " + String.join(", ", versions.keySet()) + " in different modules.",
					firstPomByArtifact.get(artifact), null, null, null, evidence, AnalysisMethod.PARSER,
					"Manage the version once in a shared parent POM or BOM."));
		});
		return findings;
	}

}
