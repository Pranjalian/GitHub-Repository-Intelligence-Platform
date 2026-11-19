package com.repointelligence.analysis.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.ModuleResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GenericStructureAnalyzerTests {

	private final GenericStructureAnalyzer analyzer = new GenericStructureAnalyzer();

	// ── Helpers ───────────────────────────────────────────────────────────────────────────────────

	private static FileInfo source(String path, String language, long sizeBytes) {
		return new FileInfo(path, language, FileType.SOURCE, sizeBytes, "hash", false, null);
	}

	private static FileInfo test(String path, String language) {
		return new FileInfo(path, language, FileType.TEST, 500, "hash", false, null);
	}

	private static FileInfo build(String path) {
		return new FileInfo(path, null, FileType.BUILD, 100, "hash", false, null);
	}

	private static FileInfo docs(String path) {
		return new FileInfo(path, "Markdown", FileType.DOCS, 200, "hash", false, null);
	}

	private AnalysisContext ctx(List<FileInfo> files) {
		return new AnalysisContext(null, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40),
				mock(WorkspaceView.class), files, List.of(), null, null, null);
	}

	private AnalysisContext ctxWith(List<FileInfo> files, WorkspaceView ws) {
		return new AnalysisContext(null, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40), ws, files, List.of(),
				null, null, null);
	}

	@Test
	void applicationJavaWithoutMainIsNotAnEntryPoint() throws Exception {
		List<FileInfo> files = List.of(source("src/main/java/com/example/FooApplication.java", "Java", 1000));
		WorkspaceView ws = mock(WorkspaceView.class);
		org.mockito.Mockito.when(ws.readString(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn("public class FooApplication { }");

		assertThat(analyzer.analyze(ctxWith(files, ws)).findings())
			.filteredOn(f -> "entry-point".equals(f.ruleId()))
			.isEmpty();
	}

	private FindingResult findByRule(AnalyzerResult result, String ruleId) {
		return result.findings()
			.stream()
			.filter(f -> ruleId.equals(f.ruleId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No finding with ruleId=" + ruleId));
	}

	// ── Applicability ─────────────────────────────────────────────────────────────────────────────

	@Test
	void isAlwaysApplicable() {
		assertThat(analyzer.applicability(ctx(List.of())).kind()).isEqualTo(Applicability.Kind.APPLICABLE);
		assertThat(analyzer.applicability(ctx(List.of(source("Main.java", "Java", 100)))).kind())
			.isEqualTo(Applicability.Kind.APPLICABLE);
	}

	// ── Module detection ──────────────────────────────────────────────────────────────────────────

	@Test
	void detectsRootModuleFromPomXml() {
		List<FileInfo> files = List.of(build("pom.xml"),
				source("src/main/java/com/example/App.java", "Java", 1000));

		AnalyzerResult result = analyzer.analyze(ctx(files));

		assertThat(result.modules()).hasSize(1);
		ModuleResult module = result.modules().get(0);
		assertThat(module.moduleName()).isEqualTo("(root)");
		assertThat(module.buildFilePath()).isEqualTo("pom.xml");
		assertThat(module.evidence()).isNotEmpty();
	}

	@Test
	void detectsMultipleModulesFromMultiModuleProject() {
		List<FileInfo> files = List.of(build("pom.xml"), build("backend/pom.xml"), build("frontend/package.json"),
				source("backend/src/main/java/Main.java", "Java", 500),
				source("frontend/src/index.ts", "TypeScript", 300));

		AnalyzerResult result = analyzer.analyze(ctx(files));

		assertThat(result.modules()).hasSize(3);
		assertThat(result.modules()).extracting(ModuleResult::moduleName)
			.containsExactlyInAnyOrder("(root)", "backend", "frontend");
	}

	@Test
	void detectsGradleModule() {
		List<FileInfo> files = List.of(build("build.gradle"), source("src/main/java/App.java", "Java", 800));

		AnalyzerResult result = analyzer.analyze(ctx(files));

		assertThat(result.modules()).hasSize(1);
		assertThat(result.modules().get(0).buildFilePath()).isEqualTo("build.gradle");
	}

	@Test
	void detectsCsprojModule() {
		List<FileInfo> files = List.of(build("MyApp.csproj"), source("Program.cs", "C#", 500));

		AnalyzerResult result = analyzer.analyze(ctx(files));

		assertThat(result.modules()).hasSize(1);
		assertThat(result.modules().get(0).buildFilePath()).isEqualTo("MyApp.csproj");
	}

	@Test
	void returnsEmptyModulesWhenNoBuildManifests() {
		List<FileInfo> files = List.of(source("Main.java", "Java", 500), docs("README.md"));

		assertThat(analyzer.analyze(ctx(files)).modules()).isEmpty();
	}

	// ── Size statistics ───────────────────────────────────────────────────────────────────────────

	@Test
	void computesSizeStatisticsCorrectly() {
		List<FileInfo> files = List.of(source("A.java", "Java", 100), source("B.java", "Java", 300),
				source("C.java", "Java", 200));

		FindingResult summary = findByRule(analyzer.analyze(ctx(files)), "structure-summary");
		@SuppressWarnings("unchecked")
		Map<String, Object> sizes = (Map<String, Object>) summary.evidence().get("sizeStats");

		assertThat((long) sizes.get("minBytes")).isEqualTo(100L);
		assertThat((long) sizes.get("maxBytes")).isEqualTo(300L);
		assertThat((long) sizes.get("medianBytes")).isEqualTo(200L);
		assertThat((long) sizes.get("meanBytes")).isEqualTo(200L);
	}

	@Test
	void totalFilesCountIsCorrect() {
		List<FileInfo> files = List.of(source("A.java", "Java", 100), source("B.java", "Java", 200),
				docs("README.md"));

		FindingResult summary = findByRule(analyzer.analyze(ctx(files)), "structure-summary");

		assertThat(summary.evidence().get("totalFiles")).isEqualTo(3);
	}

	@Test
	void filesByTypeCountsAreCorrect() {
		List<FileInfo> files = List.of(source("A.java", "Java", 100), source("B.java", "Java", 100),
				test("ATest.java", "Java"), docs("README.md"), build("pom.xml"));

		FindingResult summary = findByRule(analyzer.analyze(ctx(files)), "structure-summary");
		@SuppressWarnings("unchecked")
		Map<String, Long> byType = (Map<String, Long>) summary.evidence().get("filesByType");

		assertThat(byType.get("source")).isEqualTo(2L);
		assertThat(byType.get("test")).isEqualTo(1L);
		assertThat(byType.get("docs")).isEqualTo(1L);
		assertThat(byType.get("build")).isEqualTo(1L);
	}

	// ── Entry points ──────────────────────────────────────────────────────────────────────────────

	@Test
	void detectsSpringBootApplicationEntryPoint() throws Exception {
		List<FileInfo> files = List.of(source("src/main/java/com/example/MyApplication.java", "Java", 1000));
		WorkspaceView ws = mock(WorkspaceView.class);
		org.mockito.Mockito.when(ws.readString(org.mockito.ArgumentMatchers.eq("src/main/java/com/example/MyApplication.java"),
				org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn("public class MyApplication { public static void main(String[] args) {} }");

		AnalyzerResult result = analyzer.analyze(ctxWith(files, ws));

		List<FindingResult> entryPoints = result.findings()
			.stream()
			.filter(f -> "entry-point".equals(f.ruleId()))
			.toList();
		assertThat(entryPoints).hasSize(1);
		assertThat(entryPoints.get(0).confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(entryPoints.get(0).severity()).isEqualTo(Severity.INFO);
		assertThat(entryPoints.get(0).filePath()).contains("MyApplication.java");
	}

	@Test
	void detectsMainGoEntryPoint() {
		List<FileInfo> files = List.of(source("main.go", "Go", 500));

		List<FindingResult> entryPoints = analyzer.analyze(ctx(files))
			.findings()
			.stream()
			.filter(f -> "entry-point".equals(f.ruleId()))
			.toList();
		assertThat(entryPoints).hasSize(1);
		assertThat(entryPoints.get(0).title()).contains("main.go");
	}

	@Test
	void detectsIndexTsEntryPoint() {
		List<FileInfo> files = List.of(source("src/index.ts", "TypeScript", 300));

		assertThat(analyzer.analyze(ctx(files)).findings())
			.filteredOn(f -> "entry-point".equals(f.ruleId()))
			.hasSize(1);
	}

	@Test
	void deduplicatesEntryPoints() {
		// Same basename in two different directories — both should be listed separately.
		List<FileInfo> files = List.of(source("frontend/src/index.ts", "TypeScript", 300),
				source("backend/src/index.ts", "TypeScript", 300));

		List<FindingResult> eps = analyzer.analyze(ctx(files))
			.findings()
			.stream()
			.filter(f -> "entry-point".equals(f.ruleId()))
			.toList();
		assertThat(eps).hasSize(2);
	}

	// ── Structure summary finding ─────────────────────────────────────────────────────────────────

	@Test
	void alwaysProducesStructureSummaryFinding() {
		AnalyzerResult result = analyzer.analyze(ctx(List.of()));

		FindingResult summary = findByRule(result, "structure-summary");
		assertThat(summary.category()).isEqualTo(AnalysisCategory.STRUCTURE);
		assertThat(summary.severity()).isEqualTo(Severity.INFO);
		assertThat(summary.evidence()).containsKey("directoryTree");
		assertThat(summary.evidence()).containsKey("extensionCounts");
		assertThat(summary.evidence()).containsKey("sizeStats");
	}

	@Test
	void directoryTreeIsDepthLimited() {
		// File is at depth 5 (5 path segments before filename); tree should stop at depth 4.
		List<FileInfo> files = List.of(source("a/b/c/d/e/Deep.java", "Java", 100));

		FindingResult summary = findByRule(analyzer.analyze(ctx(files)), "structure-summary");
		@SuppressWarnings("unchecked")
		Map<String, Integer> tree = (Map<String, Integer>) summary.evidence().get("directoryTree");

		assertThat(tree).containsKey("a");
		assertThat(tree).containsKey("a/b");
		assertThat(tree).containsKey("a/b/c");
		assertThat(tree).containsKey("a/b/c/d");
		assertThat(tree).doesNotContainKey("a/b/c/d/e");
	}

	@Test
	void extensionCountsArePresentForExtractedFiles() {
		List<FileInfo> files = List.of(source("A.java", "Java", 100), source("B.java", "Java", 100),
				source("index.ts", "TypeScript", 100));

		FindingResult summary = findByRule(analyzer.analyze(ctx(files)), "structure-summary");
		@SuppressWarnings("unchecked")
		Map<String, Long> exts = (Map<String, Long>) summary.evidence().get("extensionCounts");

		assertThat(exts.get(".java")).isEqualTo(2L);
		assertThat(exts.get(".ts")).isEqualTo(1L);
	}

	// ── Result status ─────────────────────────────────────────────────────────────────────────────

	@Test
	void resultStatusIsCompleted() {
		// No warnings are added (only limitations), so status is COMPLETED.
		assertThat(analyzer.analyze(ctx(List.of())).status()).isEqualTo(ExecutionStatus.COMPLETED);
	}

	// ── Descriptor ────────────────────────────────────────────────────────────────────────────────

	@Test
	void descriptorIsCorrect() {
		assertThat(analyzer.descriptor().id()).isEqualTo(GenericStructureAnalyzer.ID);
		assertThat(analyzer.descriptor().isTechnologyIndependent()).isTrue();
		assertThat(analyzer.descriptor().limitations()).isNotEmpty();
	}

}
