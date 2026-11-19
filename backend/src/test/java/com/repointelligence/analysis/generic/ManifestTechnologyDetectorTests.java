package com.repointelligence.analysis.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManifestTechnologyDetectorTests {

	@Mock
	private WorkspaceView workspace;

	private ManifestTechnologyDetector detector;

	@BeforeEach
	void setUp() throws Exception {
		// Use the production resource so we test the real rules.
		detector = new ManifestTechnologyDetector();
		// By default, readString returns null (no version extraction needed in most tests).
		lenient().when(workspace.readString(anyString(), anyLong())).thenReturn(null);
	}

	// ── Helpers ───────────────────────────────────────────────────────────────────────────────────

	private static FileInfo file(String path) {
		return new FileInfo(path, null, FileType.BUILD, 100, "hash", false, null);
	}

	private AnalysisContext ctx(List<FileInfo> files) {
		return new AnalysisContext(null, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40), workspace, files,
				List.of(), AnalyzerSettings.defaults(), null, null);
	}

	private DetectedTechnology findByTech(List<DetectedTechnology> results, String technology) {
		return results.stream()
			.filter(t -> t.technology().equalsIgnoreCase(technology))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Technology not found: " + technology));
	}

	// ── Build tool rules ──────────────────────────────────────────────────────────────────────────

	@Test
	void detectsMavenFromPomXml() {
		List<DetectedTechnology> result = detector.detect(ctx(List.of(file("pom.xml"))));

		DetectedTechnology maven = findByTech(result, "Maven");
		assertThat(maven.kind()).isEqualTo(TechnologyKind.BUILD_TOOL);
		assertThat(maven.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(maven.evidence()).isNotEmpty();
		assertThat(maven.evidence().get(0).path()).isEqualTo("pom.xml");
	}

	@Test
	void detectsMavenFromNestedPomXml() {
		List<DetectedTechnology> result = detector
			.detect(ctx(List.of(file("backend/pom.xml"), file("backend/src/main/java/Main.java"))));

		assertThat(findByTech(result, "Maven")).isNotNull();
	}

	@Test
	void detectsGradleGroovy() {
		assertThat(detector.detect(ctx(List.of(file("build.gradle"))))).extracting(DetectedTechnology::technology)
			.contains("Gradle");
	}

	@Test
	void detectsGradleKotlin() {
		assertThat(detector.detect(ctx(List.of(file("build.gradle.kts"))))).extracting(DetectedTechnology::technology)
			.contains("Gradle");
	}

	@Test
	void detectsNpmFromPackageJson() {
		DetectedTechnology npm = findByTech(detector.detect(ctx(List.of(file("package.json")))), "npm");
		assertThat(npm.kind()).isEqualTo(TechnologyKind.PACKAGE_MANAGER);
		assertThat(npm.confidence()).isEqualTo(Confidence.DETECTED);
	}

	@Test
	void detectsPnpmFromLockFile() {
		DetectedTechnology pnpm = findByTech(detector.detect(ctx(List.of(file("pnpm-lock.yaml")))), "pnpm");
		assertThat(pnpm.kind()).isEqualTo(TechnologyKind.PACKAGE_MANAGER);
	}

	@Test
	void detectsYarnFromLockFile() {
		assertThat(detector.detect(ctx(List.of(file("yarn.lock"))))).extracting(DetectedTechnology::technology)
			.contains("Yarn");
	}

	@Test
	void detectsGoModuleFromGoMod() {
		DetectedTechnology go = findByTech(detector.detect(ctx(List.of(file("go.mod")))), "Go");
		assertThat(go.kind()).isEqualTo(TechnologyKind.LANGUAGE);
	}

	@Test
	void detectsCargoToml() {
		assertThat(detector.detect(ctx(List.of(file("Cargo.toml"))))).extracting(DetectedTechnology::technology)
			.contains("Cargo");
	}

	@Test
	void detectsGemfileForRuby() {
		assertThat(detector.detect(ctx(List.of(file("Gemfile"))))).extracting(DetectedTechnology::technology)
			.contains("Bundler");
	}

	@Test
	void detectsDotNetCsproj() {
		assertThat(detector.detect(ctx(List.of(file("MyApp.csproj"))))).extracting(DetectedTechnology::technology)
			.contains(".NET");
	}

	@Test
	void detectsDotNetSln() {
		assertThat(detector.detect(ctx(List.of(file("MyApp.sln"))))).extracting(DetectedTechnology::technology)
			.contains(".NET");
	}

	// ── Language rules ────────────────────────────────────────────────────────────────────────────

	@Test
	void detectsTypeScriptFromTsconfig() {
		DetectedTechnology ts = findByTech(detector.detect(ctx(List.of(file("tsconfig.json")))), "TypeScript");
		assertThat(ts.kind()).isEqualTo(TechnologyKind.LANGUAGE);
		assertThat(ts.confidence()).isEqualTo(Confidence.DETECTED);
	}

	@Test
	void detectsPythonFromPyprojectToml() {
		assertThat(detector.detect(ctx(List.of(file("pyproject.toml"))))).extracting(DetectedTechnology::technology)
			.contains("Python");
	}

	@Test
	void detectsPythonAssPossibleFromRequirementsTxt() {
		DetectedTechnology py = findByTech(detector.detect(ctx(List.of(file("requirements.txt")))), "Python");
		assertThat(py.confidence()).isEqualTo(Confidence.POSSIBLE);
	}

	// ── Deployment rules ──────────────────────────────────────────────────────────────────────────

	@Test
	void detectsDockerfile() {
		DetectedTechnology docker = findByTech(detector.detect(ctx(List.of(file("Dockerfile")))), "Docker");
		assertThat(docker.kind()).isEqualTo(TechnologyKind.DEPLOYMENT);
	}

	@Test
	void detectsDockerCompose() {
		assertThat(detector.detect(ctx(List.of(file("docker-compose.yml"))))).extracting(DetectedTechnology::technology)
			.contains("Docker Compose");
	}

	@Test
	void detectsHelmChart() {
		assertThat(detector.detect(ctx(List.of(file("helm/Chart.yaml"))))).extracting(DetectedTechnology::technology)
			.contains("Helm");
	}

	@Test
	void detectsGitHubActions() {
		assertThat(detector.detect(ctx(List.of(file(".github/workflows/ci.yml")))))
			.extracting(DetectedTechnology::technology)
			.contains("GitHub Actions");
	}

	@Test
	void detectsKubernetesManifests() {
		List<DetectedTechnology> result = detector
			.detect(ctx(List.of(file("k8s/deployment.yml"), file("k8s/service.yml"))));
		assertThat(result).extracting(DetectedTechnology::technology).contains("Kubernetes");
		// Kubernetes from path globs is POSSIBLE
		assertThat(findByTech(result, "Kubernetes").confidence()).isEqualTo(Confidence.POSSIBLE);
	}

	// ── Evidence ──────────────────────────────────────────────────────────────────────────────────

	@Test
	void evidenceListContainsAllMatchedPaths() {
		List<FileInfo> files = List.of(file("frontend/package.json"), file("backend/package.json"));
		DetectedTechnology npm = findByTech(detector.detect(ctx(files)), "npm");

		assertThat(npm.evidence()).extracting(e -> e.path())
			.containsExactlyInAnyOrder("frontend/package.json", "backend/package.json");
	}

	// ── Version extraction ────────────────────────────────────────────────────────────────────────

	@Test
	void extractsGoVersionFromGoMod() throws Exception {
		when(workspace.readString("go.mod", 32 * 1024L)).thenReturn("module example.com/app\n\ngo 1.21\n");

		List<DetectedTechnology> result = detector.detect(ctx(List.of(file("go.mod"))));

		DetectedTechnology go = findByTech(result, "Go");
		assertThat(go.version()).isEqualTo("1.21");
	}

	@Test
	void returnsNullVersionWhenPatternDoesNotMatch() throws Exception {
		when(workspace.readString("go.mod", 32 * 1024L)).thenReturn("module example.com/app\n");

		List<DetectedTechnology> result = detector.detect(ctx(List.of(file("go.mod"))));

		assertThat(findByTech(result, "Go").version()).isNull();
	}

	// ── Edge cases ────────────────────────────────────────────────────────────────────────────────

	@Test
	void returnsEmptyForNoMatchingFiles() {
		List<FileInfo> files = List.of(new FileInfo("src/Main.java", "Java", FileType.SOURCE, 100, "h", false, null));

		assertThat(detector.detect(ctx(files))).isEmpty();
	}

	@Test
	void descriptorIsTechnologyIndependent() {
		assertThat(detector.descriptor().isTechnologyIndependent()).isTrue();
		assertThat(detector.descriptor().id()).isEqualTo(ManifestTechnologyDetector.ID);
	}

	@Test
	void newRuleInYamlNeedsNoCodeChange() {
		ManifestTechnologyDetector custom = new ManifestTechnologyDetector("/test-technology-signatures.yml");

		List<DetectedTechnology> result = custom.detect(ctx(List.of(file("build.zig"), file("sub/build.zig"))));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).technology()).isEqualTo("Zig");
		assertThat(result.get(0).evidence()).extracting(e -> e.path()).containsExactly("build.zig", "sub/build.zig");
	}

}
