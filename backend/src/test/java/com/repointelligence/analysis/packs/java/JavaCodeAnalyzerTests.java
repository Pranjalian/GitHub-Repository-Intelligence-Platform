package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.AnalyzerSettings.AnalyzerConfig;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.support.TempWorkspace;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaCodeAnalyzerTests {

	private final JavaCodeAnalyzer analyzer = new JavaCodeAnalyzer();

	private static final String SMELLY = """
			package demo;

			public class Smelly {

				public double total(int quantity, double price, String currency, String shipping, boolean gift, boolean express,
						int tier) {
					double total = 0;
					int leftover = 42;
					if (quantity > 0) {
						if (price > 0) {
							if (currency != null) {
								if (shipping.equals("standard")) {
									if (gift) {
										total = quantity * price + 1;
									}
									else if (express) {
										total = quantity * price + 5;
									}
								}
							}
						}
					}
					try {
						total = Math.round(total);
					}
					catch (Exception e) {
					}
					return total;
				}

			}
			""";

	private static List<FindingResult> byRule(AnalyzerResult result, String rule) {
		return result.findings().stream().filter(f -> rule.equals(f.ruleId())).toList();
	}

	@Test
	void descriptorIsBasicAndDeclaresItsLimitations() {
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.BASIC);
		assertThat(analyzer.descriptor().category()).isEqualTo(AnalysisCategory.CODE_QUALITY);
		assertThat(analyzer.descriptor().limitations()).anyMatch(l -> l.contains("auxclasspath"));
		assertThat(analyzer.descriptor().limitations()).anyMatch(l -> l.contains("CPD"));
	}

	@Test
	void fixtureClassesWithKnownSmellsProduceTheExpectedRulesAndLines(@TempDir Path dir) throws Exception {
		TempWorkspace ws = new TempWorkspace(dir).add("pom.xml", "<project/>").add("src/main/java/demo/Smelly.java", SMELLY);

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
		FindingResult params = byRule(result, "ExcessiveParameterList").get(0);
		assertThat(params.filePath()).isEqualTo("src/main/java/demo/Smelly.java");
		assertThat(params.startLine()).isEqualTo(5);
		assertThat(params.category()).isEqualTo(AnalysisCategory.CODE_QUALITY);
		assertThat(params.modulePath()).isEqualTo(".");
		assertThat(byRule(result, "EmptyCatchBlock")).hasSize(1);
		assertThat(byRule(result, "EmptyCatchBlock").get(0).severity()).isEqualTo(Severity.HIGH);
		assertThat(byRule(result, "UnusedLocalVariable")).extracting(FindingResult::startLine).contains(8);
		assertThat(byRule(result, "AvoidDeeplyNestedIfStmts")).isNotEmpty();
		assertThat(byRule(result, "CyclomaticComplexity")).isNotEmpty();
	}

	@Test
	void findingsCarryEvidenceAndAnExcerpt(@TempDir Path dir) throws Exception {
		TempWorkspace ws = new TempWorkspace(dir).add("src/main/java/demo/Smelly.java", SMELLY);

		FindingResult finding = byRule(analyzer.analyze(ws.context()), "UnusedLocalVariable").get(0);

		assertThat(finding.evidence()).containsEntry("rule", "UnusedLocalVariable");
		assertThat(finding.evidence().get("excerpt").toString()).contains("int leftover = 42;");
		assertThat(finding.evidence()).containsKey("infoUrl");
		assertThat(finding.title()).isNotBlank();
		assertThat(finding.description()).isNotBlank();
		assertThat(finding.startLine()).isNotNull();
		assertThat(finding.endLine()).isNotNull();
	}

	@Test
	void pmdPriorityMapsToSeverity() {
		assertThat(JavaCodeAnalyzer.PRIORITY_TO_SEVERITY).containsEntry(1, Severity.CRITICAL)
			.containsEntry(2, Severity.HIGH)
			.containsEntry(3, Severity.MEDIUM)
			.containsEntry(4, Severity.LOW)
			.containsEntry(5, Severity.INFO);
	}

	@Test
	void notApplicableForNonJavaSnapshots(@TempDir Path dir) {
		TempWorkspace ws = new TempWorkspace(dir).add("package.json", "{}").add("src/index.ts", "export {}");

		assertThat(analyzer.applicability(ws.context()).kind()).isEqualTo(Applicability.Kind.NOT_APPLICABLE);
	}

	@Test
	void testSourcesAreSkippedUnlessEnabled(@TempDir Path dir) throws Exception {
		TempWorkspace ws = new TempWorkspace(dir).add("src/test/java/demo/SmellyTest.java", SMELLY);

		assertThat(analyzer.applicability(ws.context()).kind()).isEqualTo(Applicability.Kind.NOT_APPLICABLE);

		AnalyzerSettings includeTests = new AnalyzerSettings(
				Map.of(JavaCodeAnalyzer.ID, new AnalyzerConfig(true, Map.of("include-tests", "true"))));
		assertThat(analyzer.applicability(ws.context(includeTests, null, com.repointelligence.analysis.spi.CancellationToken.NONE))
			.isApplicable()).isTrue();
	}

	@Test
	void aFileThatFailsToParseIsAWarningNotAFailedRun(@TempDir Path dir) throws Exception {
		TempWorkspace ws = new TempWorkspace(dir).add("src/main/java/demo/Broken.java", "package demo; public class Broken { void x( {")
			.add("src/main/java/demo/Smelly.java", SMELLY);

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(result.warnings()).anyMatch(w -> w.contains("Broken.java"));
		assertThat(byRule(result, "EmptyCatchBlock")).as("other files are still analyzed").hasSize(1);
	}

	@Test
	void theRulesetLoadsWithoutConfigurationErrors(@TempDir Path dir) throws Exception {
		TempWorkspace ws = new TempWorkspace(dir).add("src/main/java/demo/Ok.java", "package demo;\n\npublic class Ok {\n}\n");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.warnings()).noneMatch(w -> w.contains("ruleset problem"));
		assertThat(result.findings()).isEmpty();
		assertThat(result.configSummary()).containsEntry("ruleset", JavaCodeAnalyzer.RULESET);
	}

	@Test
	void cancellationIsHonouredBetweenBatches(@TempDir Path dir) throws Exception {
		TempWorkspace ws = new TempWorkspace(dir).add("src/main/java/demo/Smelly.java", SMELLY);

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> analyzer.analyze(ws.context(AnalyzerSettings.defaults(), null, () -> true)))
			.isInstanceOf(com.repointelligence.analysis.spi.AnalysisCancelledException.class);
	}

	@Test
	void modulesAreAssignedFromTheNearestPomDirectory() {
		java.util.Set<String> directories = new java.util.TreeSet<>(java.util.Comparator.comparing(String::length)
			.reversed()
			.thenComparing(java.util.Comparator.naturalOrder()));
		directories.addAll(List.of("", "core", "core/inner"));

		assertThat(JavaCodeAnalyzer.moduleOf("core/inner/src/A.java", directories)).isEqualTo("core/inner");
		assertThat(JavaCodeAnalyzer.moduleOf("core/src/A.java", directories)).isEqualTo("core");
		assertThat(JavaCodeAnalyzer.moduleOf("web/src/A.java", directories)).isEqualTo(".");
	}

}
