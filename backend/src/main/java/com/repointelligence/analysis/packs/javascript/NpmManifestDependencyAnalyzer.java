package com.repointelligence.analysis.packs.javascript;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DependencyAnalyzer;
import com.repointelligence.analysis.spi.DependencyResult;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lists the direct dependencies declared in every {@code package.json}. Version ranges are kept exactly
 * as declared (a range is not a resolved version); lockfiles are not read, so nothing is resolved.
 */
@Component
public class NpmManifestDependencyAnalyzer implements DependencyAnalyzer {

	public static final String ID = "npm-manifest-dependency-analyzer";

	private static final String VERSION = "1.0.0";

	private static final long MAX_MANIFEST_BYTES = 1024 * 1024L;

	/** package.json section → scope label shown to users. */
	private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

	static {
		SECTIONS.put("dependencies", "runtime");
		SECTIONS.put("devDependencies", "dev");
		SECTIONS.put("peerDependencies", "peer");
		SECTIONS.put("optionalDependencies", "optional");
	}

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "npm Manifest Dependency Analyzer", VERSION, AnalysisCategory.DEPENDENCIES, CapabilityLevel.BASIC)
		.languages("JavaScript", "TypeScript")
		.frameworks("npm", "pnpm", "Yarn")
		.manifests("package.json")
		.description("Lists direct dependencies from package.json (dependencies, devDependencies, "
				+ "peerDependencies, optionalDependencies) with version ranges as declared.")
		.limitations("Lockfiles are not read, so versions are ranges as declared, not the installed versions.",
				"Transitive dependencies are not listed.")
		.build();

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		return manifests(context.files()).isEmpty() ? Applicability.notApplicable() : Applicability.applicable();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		int manifests = 0;
		int dependencies = 0;
		for (String path : manifests(context.files())) {
			context.cancellation().throwIfCancelled();
			JsonNode root;
			try {
				root = mapper.readTree(context.workspace().readString(path, MAX_MANIFEST_BYTES));
			}
			catch (Exception ex) {
				result.warning(path + ": not valid JSON (" + ex.getClass().getSimpleName() + "); its dependencies are missing from the inventory.");
				continue;
			}
			if (root == null || !root.isObject()) {
				result.warning(path + ": not a JSON object; its dependencies are missing from the inventory.");
				continue;
			}
			manifests++;
			String modulePath = modulePath(path);
			for (Map.Entry<String, String> section : SECTIONS.entrySet()) {
				JsonNode node = root.get(section.getKey());
				if (node == null || !node.isObject()) {
					continue;
				}
				for (Map.Entry<String, JsonNode> entry : node.properties()) {
					String fullName = entry.getKey();
					String namespace = null;
					String name = fullName;
					if (fullName.startsWith("@") && fullName.contains("/")) {
						namespace = fullName.substring(0, fullName.indexOf('/'));
						name = fullName.substring(fullName.indexOf('/') + 1);
					}
					String range = entry.getValue().isString() ? entry.getValue().asString() : entry.getValue().toString();
					result.dependency(new DependencyResult(Ecosystem.NPM, path, modulePath, namespace, name, range, null,
							VersionSource.DECLARED, section.getValue(), true, false, VulnerabilityStatus.NOT_CHECKED,
							List.of(), null));
					dependencies++;
				}
			}
		}
		result.configSummary(Map.of("packageJsonFiles", manifests, "dependencies", dependencies));
		DESCRIPTOR.limitations().forEach(result::limitation);
		return result.build();
	}

	private static List<String> manifests(List<FileInfo> files) {
		return files.stream()
			.filter(FileInfo::extracted)
			.map(FileInfo::path)
			.filter(path -> "package.json".equals(path.substring(path.lastIndexOf('/') + 1)))
			.sorted()
			.toList();
	}

	private static String modulePath(String manifestPath) {
		int slash = manifestPath.lastIndexOf('/');
		return slash < 0 ? "." : manifestPath.substring(0, slash);
	}

}
