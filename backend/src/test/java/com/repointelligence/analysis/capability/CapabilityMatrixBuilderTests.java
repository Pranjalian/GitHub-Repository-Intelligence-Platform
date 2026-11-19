package com.repointelligence.analysis.capability;

import static com.repointelligence.support.StubAnalyzers.analyzer;
import static com.repointelligence.support.StubAnalyzers.context;
import static com.repointelligence.support.StubAnalyzers.tech;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.registry.AnalyzerRegistry;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.support.StubAnalyzers.StubAnalyzer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CapabilityMatrixBuilderTests {

	private static final DetectedTechnology JAVA = tech("Java", TechnologyKind.LANGUAGE);

	private static final DetectedTechnology TYPESCRIPT = tech("TypeScript", TechnologyKind.LANGUAGE);

	private static final DetectedTechnology MAVEN = tech("Maven", TechnologyKind.BUILD_TOOL);

	private static StubAnalyzer genericStructure() {
		return analyzer("generic-structure", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC);
	}

	private static StubAnalyzer genericSecrets() {
		return analyzer("generic-secrets", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC);
	}

	private static StubAnalyzer pmd() {
		return analyzer("pmd-java", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC).languages("Java");
	}

	private static StubAnalyzer mavenDeps() {
		return analyzer("maven-dependencies", AnalysisCategory.DEPENDENCIES, CapabilityLevel.DEEP).languages("Java")
			.frameworks("Maven");
	}

	private static StubAnalyzer javaTests() {
		return analyzer("java-tests", AnalysisCategory.TESTS, CapabilityLevel.BASIC).languages("Java");
	}

	private static List<Analyzer> javaPack() {
		return List.of(genericStructure(), genericSecrets(), pmd(), mavenDeps(), javaTests());
	}

	private List<CapabilityRow> build(List<Analyzer> analyzers, List<DetectedTechnology> technologies) {
		return build(analyzers, technologies, AnalyzerSettings.defaults());
	}

	private List<CapabilityRow> build(List<Analyzer> analyzers, List<DetectedTechnology> technologies,
			AnalyzerSettings settings) {
		AnalyzerRegistry registry = new AnalyzerRegistry(List.of(), analyzers, settings);
		return new CapabilityMatrixBuilder(registry, settings).build(technologies, registry.evaluate(context()));
	}

	private static Optional<CapabilityRow> row(List<CapabilityRow> rows, String technology, AnalysisCategory category,
			String analyzerId) {
		return rows.stream()
			.filter(r -> r.technology().equals(technology) && r.category() == category
					&& Objects.equals(r.analyzerId(), analyzerId))
			.findFirst();
	}

	// ---- the plan's matrix: {Java, Maven, TypeScript} ------------------------------------------

	@Test
	void javaMavenAndTypeScriptGetDeepBasicAndUnsupportedRows() {
		List<CapabilityRow> rows = build(javaPack(), List.of(JAVA, MAVEN, TYPESCRIPT));

		// Java: deep dependencies, basic code quality and tests, generic structure
		assertThat(row(rows, "Java", AnalysisCategory.DEPENDENCIES, "maven-dependencies").orElseThrow()
			.capabilityLevel()).isEqualTo(CapabilityLevel.DEEP);
		assertThat(row(rows, "Java", AnalysisCategory.CODE_QUALITY, "pmd-java").orElseThrow().capabilityLevel())
			.isEqualTo(CapabilityLevel.BASIC);
		assertThat(row(rows, "Java", AnalysisCategory.TESTS, "java-tests").orElseThrow().capabilityLevel())
			.isEqualTo(CapabilityLevel.BASIC);
		assertThat(row(rows, "Java", AnalysisCategory.STRUCTURE, "generic-structure").orElseThrow().capabilityLevel())
			.isEqualTo(CapabilityLevel.GENERIC);
		// Maven: named by the dependency analyzer
		CapabilityRow maven = row(rows, "Maven", AnalysisCategory.DEPENDENCIES, "maven-dependencies").orElseThrow();
		assertThat(maven.capabilityLevel()).isEqualTo(CapabilityLevel.DEEP);
		assertThat(maven.executionStatus()).isEqualTo(ExecutionStatus.NOT_ANALYZED);
		// TypeScript: generic coverage only, honest UNSUPPORTED for everything language-specific
		for (AnalysisCategory category : List.of(AnalysisCategory.DEPENDENCIES, AnalysisCategory.CODE_QUALITY,
				AnalysisCategory.TESTS)) {
			CapabilityRow unsupported = row(rows, "TypeScript", category, null).orElseThrow();
			assertThat(unsupported.capabilityLevel()).isEqualTo(CapabilityLevel.UNSUPPORTED);
			assertThat(unsupported.executionStatus()).isEqualTo(ExecutionStatus.NOT_SUPPORTED);
			assertThat(unsupported.limitationReason()).isEqualTo("no analyzer pack for TypeScript");
		}
		assertThat(row(rows, "TypeScript", AnalysisCategory.STRUCTURE, "generic-structure")).isPresent();
		assertThat(row(rows, "TypeScript", AnalysisCategory.SECURITY, "generic-secrets")).isPresent();
		// Maven (a build tool) gets no "unsupported" noise for categories it has no analyzer for
		assertThat(rows).noneMatch(r -> r.technology().equals("Maven") && r.analyzerId() == null);
		// the TypeScript row set is complete: all five categories are present
		assertThat(rows.stream().filter(r -> r.technology().equals("TypeScript")).map(CapabilityRow::category))
			.containsExactlyInAnyOrder(AnalysisCategory.STRUCTURE, AnalysisCategory.DEPENDENCIES,
					AnalysisCategory.CODE_QUALITY, AnalysisCategory.SECURITY, AnalysisCategory.TESTS);
	}

	@Test
	void undetectedTechnologiesProduceNoRows() {
		List<CapabilityRow> rows = build(javaPack(), List.of(TYPESCRIPT));

		assertThat(rows).noneMatch(r -> r.technology().equals("Java"));
		assertThat(rows).noneMatch(r -> r.technology().equals("Maven"));
		assertThat(rows).noneMatch(r -> "pmd-java".equals(r.analyzerId()));
		assertThat(rows).noneMatch(r -> "maven-dependencies".equals(r.analyzerId()));
	}

	@Test
	void noTechnologiesAtAllMeansOnlyGenericCoverageUnderAPlaceholder() {
		List<CapabilityRow> rows = build(javaPack(), List.of());

		assertThat(rows).extracting(CapabilityRow::technology).containsOnly(CapabilityMatrixBuilder.NO_LANGUAGE);
		assertThat(rows).extracting(CapabilityRow::analyzerId)
			.containsExactlyInAnyOrder("generic-secrets", "generic-structure");
	}

	@Test
	void informationalTechnologiesGetNoRows() {
		List<CapabilityRow> rows = build(javaPack(), List.of(JAVA, tech("PostgreSQL", TechnologyKind.DATABASE),
				tech("JUnit 5", TechnologyKind.TEST_FRAMEWORK), tech("Docker", TechnologyKind.DEPLOYMENT)));

		assertThat(rows).extracting(CapabilityRow::technology).containsOnly("Java");
	}

	@Test
	void technologiesDetectedTwiceAreListedOnce() {
		List<CapabilityRow> rows = build(javaPack(), List.of(JAVA, tech("java", TechnologyKind.LANGUAGE)));

		assertThat(rows.stream().filter(r -> r.category() == AnalysisCategory.CODE_QUALITY)).hasSize(1);
	}

	// ---- availability --------------------------------------------------------------------------

	@Test
	void notApplicableAnalyzersHaveNoRowAndLeaveAnUnsupportedGap() {
		StubAnalyzer noJavaSources = pmd().applicability(Applicability.notApplicable());

		List<CapabilityRow> rows = build(List.of(genericStructure(), noJavaSources), List.of(JAVA));

		assertThat(rows).noneMatch(r -> "pmd-java".equals(r.analyzerId()));
		CapabilityRow gap = row(rows, "Java", AnalysisCategory.CODE_QUALITY, null).orElseThrow();
		assertThat(gap.capabilityLevel()).isEqualTo(CapabilityLevel.UNSUPPORTED);
	}

	@Test
	void anUnavailableAnalyzerAppearsWithItsReasonNextToTheOneThatStillRuns() {
		StubAnalyzer semgrep = analyzer("semgrep", AnalysisCategory.SECURITY, CapabilityLevel.BASIC).languages("Java")
			.applicability(Applicability.unavailable("Semgrep CLI not found"));

		List<CapabilityRow> rows = build(List.of(genericSecrets(), semgrep), List.of(JAVA));

		CapabilityRow unavailable = row(rows, "Java", AnalysisCategory.SECURITY, "semgrep").orElseThrow();
		assertThat(unavailable.capabilityLevel()).isEqualTo(CapabilityLevel.UNAVAILABLE);
		assertThat(unavailable.executionStatus()).isEqualTo(ExecutionStatus.UNAVAILABLE);
		assertThat(unavailable.limitationReason()).isEqualTo("Semgrep CLI not found");
		CapabilityRow generic = row(rows, "Java", AnalysisCategory.SECURITY, "generic-secrets").orElseThrow();
		assertThat(generic.executionStatus()).isEqualTo(ExecutionStatus.NOT_ANALYZED);
		assertThat(generic.capabilityLevel()).isEqualTo(CapabilityLevel.GENERIC);
	}

	@Test
	void aDisabledAnalyzerIsReportedAsNotConfigured() {
		Map<String, AnalyzerSettings.AnalyzerConfig> configs = new HashMap<>();
		configs.put("pmd-java", new AnalyzerSettings.AnalyzerConfig(false, Map.of()));

		List<CapabilityRow> rows = build(List.of(pmd()), List.of(JAVA), new AnalyzerSettings(configs));

		CapabilityRow disabled = row(rows, "Java", AnalysisCategory.CODE_QUALITY, "pmd-java").orElseThrow();
		assertThat(disabled.capabilityLevel()).isEqualTo(CapabilityLevel.UNAVAILABLE);
		assertThat(disabled.executionStatus()).isEqualTo(ExecutionStatus.NOT_CONFIGURED);
		assertThat(disabled.limitationReason()).isEqualTo("disabled by configuration");
	}

	@Test
	void missingApplicabilityIsAProgrammingError() {
		AnalyzerRegistry registry = new AnalyzerRegistry(List.of(), List.of(pmd()), AnalyzerSettings.defaults());
		CapabilityMatrixBuilder builder = new CapabilityMatrixBuilder(registry, AnalyzerSettings.defaults());

		assertThatThrownBy(() -> builder.build(List.of(JAVA), Map.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("pmd-java");
	}

	// ---- ordering and outcomes -----------------------------------------------------------------

	@Test
	void rowsAreInADeterministicOrder() {
		List<CapabilityRow> first = build(javaPack(), List.of(TYPESCRIPT, JAVA, MAVEN));
		List<CapabilityRow> second = build(
				List.of(javaTests(), mavenDeps(), pmd(), genericSecrets(), genericStructure()),
				List.of(MAVEN, JAVA, TYPESCRIPT));

		assertThat(first).isEqualTo(second);
		assertThat(first.get(0).technology()).isEqualTo("Java");
	}

	@Test
	void outcomesFillInExecutionStatusesOnlyForRowsThatWereToRun() {
		AnalyzerSettings settings = AnalyzerSettings.defaults();
		StubAnalyzer semgrep = analyzer("semgrep", AnalysisCategory.SECURITY, CapabilityLevel.BASIC).languages("Java")
			.applicability(Applicability.unavailable("Semgrep CLI not found"));
		AnalyzerRegistry registry = new AnalyzerRegistry(List.of(), List.of(pmd(), genericSecrets(), semgrep),
				settings);
		CapabilityMatrixBuilder builder = new CapabilityMatrixBuilder(registry, settings);
		List<CapabilityRow> rows = builder.build(List.of(JAVA), registry.evaluate(context()));

		List<CapabilityRow> updated = builder.applyOutcomes(rows, Map.of("pmd-java",
				new ExecutionOutcome(ExecutionStatus.COMPLETED_WITH_WARNINGS, "1 file failed to parse"),
				"generic-secrets", new ExecutionOutcome(ExecutionStatus.FAILED, "scanner crashed"),
				// an outcome for an analyzer whose row was already UNAVAILABLE must not overwrite it
				"semgrep", new ExecutionOutcome(ExecutionStatus.COMPLETED, null)));

		CapabilityRow pmdRow = row(updated, "Java", AnalysisCategory.CODE_QUALITY, "pmd-java").orElseThrow();
		assertThat(pmdRow.executionStatus()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(pmdRow.limitationReason()).isEqualTo("1 file failed to parse");
		assertThat(row(updated, "Java", AnalysisCategory.SECURITY, "generic-secrets").orElseThrow().executionStatus())
			.isEqualTo(ExecutionStatus.FAILED);
		CapabilityRow semgrepRow = row(updated, "Java", AnalysisCategory.SECURITY, "semgrep").orElseThrow();
		assertThat(semgrepRow.executionStatus()).isEqualTo(ExecutionStatus.UNAVAILABLE);
		assertThat(semgrepRow.limitationReason()).isEqualTo("Semgrep CLI not found");
		// unsupported rows have no analyzer and are untouched
		assertThat(row(updated, "Java", AnalysisCategory.TESTS, null).orElseThrow().executionStatus())
			.isEqualTo(ExecutionStatus.NOT_SUPPORTED);
		// the input list is not modified
		assertThat(row(rows, "Java", AnalysisCategory.CODE_QUALITY, "pmd-java").orElseThrow().executionStatus())
			.isEqualTo(ExecutionStatus.NOT_ANALYZED);
	}

}
