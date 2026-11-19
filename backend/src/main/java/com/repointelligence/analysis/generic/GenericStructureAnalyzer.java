package com.repointelligence.analysis.generic;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.ModuleResult;
import com.repointelligence.analysis.spi.RepositoryAnalyzer;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.FileType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Technology-independent structure analysis that runs for every repository.
 *
 * <p>Produces:
 * <ul>
 *   <li>Module boundaries: directories that contain a build manifest become {@link ModuleResult}s.</li>
 *   <li>A structure-summary {@link FindingResult} (category {@code STRUCTURE}, severity {@code INFO})
 *       containing a depth-limited directory tree, file-type counts, extension distribution and size
 *       statistics.</li>
 *   <li>Entry-point hints as additional {@code STRUCTURE/INFO} findings.</li>
 * </ul>
 *
 * <p>Analyzers never persist anything; the orchestrator writes results to the database.
 */
@Component
public class GenericStructureAnalyzer implements RepositoryAnalyzer {

	static final String ID = "generic-structure-analyzer";

	private static final String VERSION = "1.0.0";

	/** Build manifests that mark a directory as a module boundary, in priority order. */
	private static final List<String> BUILD_MANIFESTS = List.of("pom.xml", "build.gradle", "build.gradle.kts",
			"package.json", "go.mod", "Cargo.toml", "pyproject.toml", "setup.py", "requirements.txt", "*.csproj",
			"Gemfile", "build.sbt");

	/** Entry-point file names / patterns (checked against the basename, case-sensitive). */
	private static final List<String> ENTRY_POINT_BASENAMES = List.of("main.go", "index.ts", "index.js", "index.tsx",
			"app.py", "manage.py", "Program.cs", "main.py", "server.ts", "server.js", "index.py");

	/** The "*Application.java" suffix check is done separately for Spring Boot. */
	private static final String SPRING_APPLICATION_SUFFIX = "Application.java";

	private static final java.util.regex.Pattern MAIN_METHOD = java.util.regex.Pattern
		.compile("static\\s+void\\s+main\\s*\\(");

	/** How deep the directory tree summary descends. */
	private static final int TREE_MAX_DEPTH = 4;

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Generic Structure Analyzer", VERSION, AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC)
		.description("Technology-independent structure analysis: directory tree, module boundaries, "
				+ "file-type distribution, size statistics, and entry-point detection.")
		.limitations("Operates on file paths and metadata only; does not parse source code.",
				"Module responsibilities are inferred from paths, not from code semantics.")
		.build();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		// Always applicable: every repository has a file list.
		return Applicability.applicable();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		List<FileInfo> files = context.files();
		AnalyzerResult.Builder result = AnalyzerResult.builder();

		// 1. Module boundaries
		List<ModuleResult> modules = detectModules(files);
		modules.forEach(result::module);

		// 2. File-type counts
		Map<FileType, Long> typeCounts = files.stream()
			.collect(Collectors.groupingBy(f -> f.fileType() != null ? f.fileType() : FileType.OTHER,
					Collectors.counting()));

		// 3. Extension distribution (extracted files only, top 20)
		Map<String, Long> extCounts = files.stream()
			.filter(FileInfo::extracted)
			.collect(Collectors.groupingBy(f -> extension(f.path()), Collectors.counting()));

		// 4. Size statistics (all catalogued files)
		LongSummaryStatistics sizeStats = files.stream()
			.mapToLong(FileInfo::sizeBytes)
			.summaryStatistics();
		long medianBytes = median(files.stream().mapToLong(FileInfo::sizeBytes).sorted().toArray());

		// 5. Directory tree (depth-limited)
		Map<String, Integer> dirTree = buildDirTree(files, TREE_MAX_DEPTH);

		// 6. Build the structure-summary finding
		Map<String, Object> evidence = buildStructureEvidence(files, typeCounts, extCounts, sizeStats, medianBytes,
				dirTree, modules);
		result.finding(new FindingResult(AnalysisCategory.STRUCTURE, "structure-summary", Severity.INFO,
				Confidence.DETECTED, "Repository structure summary",
				"File-type distribution, size statistics and directory tree at depth " + TREE_MAX_DEPTH + ".", null,
				null, null, null, evidence, AnalysisMethod.HEURISTIC, null));

		// 7. Entry points
		detectEntryPoints(context).forEach(result::finding);

		result.limitation("Module responsibility detection is based on directory naming conventions only.");
		return result.build();
	}

	// ── Module detection ──────────────────────────────────────────────────────────────────────────

	private static List<ModuleResult> detectModules(List<FileInfo> files) {
		// A directory qualifies as a module if it contains a build manifest.
		// Map: normalized directory → list of build file paths found there.
		Map<String, List<String>> dirToBuildFiles = new LinkedHashMap<>();

		for (FileInfo file : files) {
			String path = file.path();
			String basename = basename(path);
			if (isBuildManifest(basename)) {
				String dir = parentDir(path);
				dirToBuildFiles.computeIfAbsent(dir, k -> new ArrayList<>()).add(path);
			}
		}

		List<ModuleResult> modules = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : dirToBuildFiles.entrySet()) {
			String dir = entry.getKey();
			List<String> buildFiles = entry.getValue();
			String moduleName = dir.isEmpty() ? "(root)" : basename(dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir);
			Ecosystem ecosystem = ecosystemFrom(buildFiles);
			List<Evidence> evidence = buildFiles.stream().map(p -> new Evidence(p, null, "build manifest")).toList();
			String responsibility = guessResponsibility(dir);
			modules.add(new ModuleResult(moduleName, dir.isEmpty() ? "." : dir, buildFiles.get(0), ecosystem,
					responsibility, evidence));
		}

		return modules;
	}

	private static boolean isBuildManifest(String basename) {
		for (String manifest : BUILD_MANIFESTS) {
			if (manifest.startsWith("*")) {
				String suffix = manifest.substring(1); // e.g. ".csproj"
				if (basename.endsWith(suffix)) {
					return true;
				}
			}
			else if (basename.equals(manifest)) {
				return true;
			}
		}
		return false;
	}

	private static Ecosystem ecosystemFrom(List<String> buildFiles) {
		for (String path : buildFiles) {
			String base = basename(path);
			if (base.equals("pom.xml") || base.equals("build.gradle") || base.equals("build.gradle.kts")) {
				return Ecosystem.MAVEN;
			}
			if (base.equals("package.json") || base.equals("pnpm-lock.yaml") || base.equals("yarn.lock")) {
				return Ecosystem.NPM;
			}
		}
		return null;
	}

	private static String guessResponsibility(String dir) {
		if (dir.isEmpty()) {
			return null;
		}
		String lower = dir.toLowerCase();
		if (lower.contains("frontend") || lower.contains("ui") || lower.contains("web")
				|| lower.contains("client")) {
			return "frontend";
		}
		if (lower.contains("backend") || lower.contains("server") || lower.contains("api")
				|| lower.contains("service")) {
			return "backend";
		}
		if (lower.contains("lib") || lower.contains("common") || lower.contains("shared")
				|| lower.contains("core")) {
			return "library";
		}
		return null;
	}

	// ── Entry-point detection ─────────────────────────────────────────────────────────────────────

	private static List<FindingResult> detectEntryPoints(AnalysisContext context) {
		List<FileInfo> files = context.files();
		List<FindingResult> findings = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (FileInfo file : files) {
			if (!file.extracted()) {
				continue;
			}
			String base = basename(file.path());
			boolean isEntryPoint = ENTRY_POINT_BASENAMES.contains(base)
					|| (base.endsWith(SPRING_APPLICATION_SUFFIX) && declaresMainMethod(context, file.path()));
			if (isEntryPoint && seen.add(file.path())) {
				Map<String, Object> ev = Map.of("path", file.path());
				findings.add(new FindingResult(AnalysisCategory.STRUCTURE, "entry-point", Severity.INFO,
						Confidence.LIKELY, "Potential entry point: " + base,
						"File '" + file.path() + "' matches a well-known entry-point naming convention.", file.path(),
						null, null, null, ev, AnalysisMethod.HEURISTIC, null));
			}
		}
		return findings;
	}

	/** A "*Application.java" file only counts as an entry point if it declares a main method. */
	private static boolean declaresMainMethod(AnalysisContext context, String path) {
		try {
			String content = context.workspace().readString(path, 64 * 1024L);
			return content != null && MAIN_METHOD.matcher(content).find();
		}
		catch (Exception ex) {
			return false;
		}
	}

	// ── Directory tree ────────────────────────────────────────────────────────────────────────────

	/**
	 * Returns a map of {@code dirPath → fileCount} for all directories up to {@code maxDepth}. The
	 * map is sorted lexicographically for stable serialization.
	 */
	private static Map<String, Integer> buildDirTree(List<FileInfo> files, int maxDepth) {
		Map<String, Integer> counts = new TreeMap<>();
		for (FileInfo file : files) {
			String path = file.path().replace('\\', '/');
			String[] parts = path.split("/");
			// parts[last] is the filename; parts[0..last-1] are directory segments.
			StringBuilder sb = new StringBuilder();
			for (int depth = 0; depth < parts.length - 1 && depth < maxDepth; depth++) {
				if (sb.length() > 0) {
					sb.append('/');
				}
				sb.append(parts[depth]);
				counts.merge(sb.toString(), 1, Integer::sum);
			}
		}
		return counts;
	}

	// ── Evidence map ─────────────────────────────────────────────────────────────────────────────

	private static Map<String, Object> buildStructureEvidence(List<FileInfo> files,
			Map<FileType, Long> typeCounts, Map<String, Long> extCounts, LongSummaryStatistics sizeStats,
			long medianBytes, Map<String, Integer> dirTree, List<ModuleResult> modules) {

		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("totalFiles", files.size());
		ev.put("totalBytes", sizeStats.getSum());

		// File-type counts
		Map<String, Long> typeMap = new LinkedHashMap<>();
		for (FileType ft : FileType.values()) {
			long count = typeCounts.getOrDefault(ft, 0L);
			if (count > 0) {
				typeMap.put(ft.name().toLowerCase(), count);
			}
		}
		ev.put("filesByType", typeMap);

		// Size statistics
		Map<String, Object> sizes = new LinkedHashMap<>();
		sizes.put("minBytes", sizeStats.getCount() > 0 ? sizeStats.getMin() : 0);
		sizes.put("maxBytes", sizeStats.getCount() > 0 ? sizeStats.getMax() : 0);
		sizes.put("meanBytes", sizeStats.getCount() > 0 ? (long) sizeStats.getAverage() : 0);
		sizes.put("medianBytes", medianBytes);
		ev.put("sizeStats", sizes);

		// Top 20 extensions by count
		Map<String, Long> topExts = extCounts.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(20)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
		ev.put("extensionCounts", topExts);

		// Directory tree
		ev.put("directoryTree", dirTree);

		// Module count
		ev.put("moduleCount", modules.size());

		return ev;
	}

	// ── Utility helpers ───────────────────────────────────────────────────────────────────────────

	private static String basename(String path) {
		String normalised = path.replace('\\', '/');
		int slash = normalised.lastIndexOf('/');
		return slash >= 0 ? normalised.substring(slash + 1) : normalised;
	}

	private static String parentDir(String path) {
		String normalised = path.replace('\\', '/');
		int slash = normalised.lastIndexOf('/');
		return slash >= 0 ? normalised.substring(0, slash) : "";
	}

	private static String extension(String path) {
		String base = basename(path);
		int dot = base.lastIndexOf('.');
		return dot >= 0 && dot < base.length() - 1 ? base.substring(dot).toLowerCase() : "(no extension)";
	}

	private static long median(long[] sorted) {
		if (sorted.length == 0) {
			return 0;
		}
		int mid = sorted.length / 2;
		return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
	}

}
