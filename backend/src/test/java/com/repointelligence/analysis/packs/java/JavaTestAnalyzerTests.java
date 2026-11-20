package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.MappingMethod;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.TestResult;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import com.repointelligence.support.InMemoryWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaTestAnalyzerTests {

	private final JavaTestAnalyzer analyzer = new JavaTestAnalyzer();

	private static final String JUNIT5 = """
			package demo;

			import org.junit.jupiter.api.Test;
			import org.junit.jupiter.params.ParameterizedTest;
			import org.junit.jupiter.api.RepeatedTest;
			import org.junit.jupiter.api.TestFactory;

			class OrderServiceTests {
				@Test void one() {}
				@Test void two() {}
				@ParameterizedTest void three() {}
				@RepeatedTest(3) void four() {}
				@TestFactory Object five() { return null; }
				// @Test void commentedOut() {}
				/* @Test void alsoCommentedOut() {} */
				String text = "@Test void inString() {}";
				void helper() {}
			}
			""";

	private static final String JUNIT4 = """
			package demo;

			import org.junit.Test;

			public class LegacyTest {
				@Test public void a() {}
				@Test public void b() {}
			}
			""";

	private static final String TESTNG = """
			package demo;

			import org.testng.annotations.Test;

			public class ReportIT {
				@Test public void only() {}
			}
			""";

	private static TestResult find(AnalyzerResult result, String symbol) {
		return result.testRecords().stream().filter(t -> symbol.equals(t.testSymbol())).findFirst().orElseThrow();
	}

	@Test
	void countsTestMethodsAcrossJUnit4JUnit5AndTestNGIgnoringComments() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/test/java/demo/OrderServiceTests.java", JUNIT5)
			.add("src/test/java/demo/LegacyTest.java", JUNIT4)
			.add("src/test/java/demo/ReportIT.java", TESTNG);

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(find(result, "demo.OrderServiceTests").testMethodCount()).isEqualTo(5);
		assertThat(find(result, "demo.OrderServiceTests").framework()).isEqualTo("JUnit 5");
		assertThat(find(result, "demo.LegacyTest").testMethodCount()).isEqualTo(2);
		assertThat(find(result, "demo.LegacyTest").framework()).isEqualTo("JUnit 4");
		assertThat(find(result, "demo.ReportIT").framework()).isEqualTo("TestNG");
		assertThat(result.configSummary()).containsEntry("testClasses", 3).containsEntry("testMethods", 8);
	}

	@Test
	void mapsTestsToProductionClassesByNamingConventionInTheSamePackage() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/main/java/demo/OrderService.java", "package demo;\npublic class OrderService {}\n")
			.add("src/main/java/demo/Legacy.java", "package demo;\npublic class Legacy {}\n")
			.add("src/main/java/other/OrderService.java", "package other;\npublic class OrderService {}\n")
			.add("src/test/java/demo/OrderServiceTests.java", JUNIT5)
			.add("src/test/java/demo/LegacyTest.java", JUNIT4);

		AnalyzerResult result = analyzer.analyze(ws.context());

		TestResult mapped = find(result, "demo.OrderServiceTests");
		assertThat(mapped.productionSymbol()).isEqualTo("demo.OrderService");
		assertThat(mapped.mappingMethod()).isEqualTo(MappingMethod.NAMING_CONVENTION);
		assertThat(mapped.confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(find(result, "demo.LegacyTest").productionSymbol()).isEqualTo("demo.Legacy");
	}

	@Test
	void classesWithoutAnObviousTestAreInfoFindingsWithPossibleConfidence() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/main/java/demo/OrderService.java", "package demo;\npublic class OrderService {}\n")
			.add("src/main/java/demo/Untested.java", "package demo;\npublic class Untested {}\n")
			.add("src/main/java/demo/Api.java", "package demo;\npublic interface Api {}\n")
			.add("src/test/java/demo/OrderServiceTests.java", JUNIT5);

		List<FindingResult> findings = analyzer.analyze(ws.context()).findings();

		assertThat(findings).hasSize(1);
		FindingResult finding = findings.get(0);
		assertThat(finding.ruleId()).isEqualTo("no-test-found");
		assertThat(finding.severity()).isEqualTo(Severity.INFO);
		assertThat(finding.confidence()).isEqualTo(Confidence.POSSIBLE);
		assertThat(finding.filePath()).isEqualTo("src/main/java/demo/Untested.java");
		assertThat(finding.title()).contains("Untested");
	}

	@Test
	void frameworkDetectionAndNaming() {
		assertThat(JavaTestAnalyzer.productionNameFor("FooTest")).isEqualTo("Foo");
		assertThat(JavaTestAnalyzer.productionNameFor("FooTests")).isEqualTo("Foo");
		assertThat(JavaTestAnalyzer.productionNameFor("FooIT")).isEqualTo("Foo");
		assertThat(JavaTestAnalyzer.productionNameFor("TestFoo")).isEqualTo("Foo");
		assertThat(JavaTestAnalyzer.productionNameFor("Helper")).isNull();
		assertThat(JavaTestAnalyzer.frameworkOf("org.junit.Test", List.of(), List.of())).isEqualTo("JUnit 4");
		assertThat(JavaTestAnalyzer.frameworkOf("Test", List.of("org.junit.jupiter.api"), List.of(true))).isEqualTo("JUnit 5");
		assertThat(JavaTestAnalyzer.frameworkOf("Test", List.of(), List.of())).isNull();
	}

	@Test
	void missingReportMeansNoCoverageNumbersAndTheUnavailableMessage() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/test/java/demo/LegacyTest.java", JUNIT4);

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.coverageRecords()).isEmpty();
		assertThat(result.limitations()).contains(JavaTestAnalyzer.COVERAGE_UNAVAILABLE);
		assertThat(JavaTestAnalyzer.COVERAGE_UNAVAILABLE).isEqualTo(
				"Coverage unavailable: no JaCoCo report found in the repository. Tests are not executed in MVP 1.");
	}

	@Test
	void aCommittedJacocoReportIsReadAndNotedAsPossiblyStale() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/test/java/demo/LegacyTest.java", JUNIT4)
			.add("target/site/jacoco/jacoco.xml", JacocoReportParserTests.REPORT);

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.coverageRecords()).isNotEmpty();
		assertThat(result.limitations()).anyMatch(l -> l.contains("may be stale"));
		assertThat(result.limitations()).doesNotContain(JavaTestAnalyzer.COVERAGE_UNAVAILABLE);
	}

	@Test
	void aBrokenReportIsAWarningAndShowsNoCoverage() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/test/java/demo/LegacyTest.java", JUNIT4)
			.add("jacoco.xml", "<report><oops");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.warnings()).anyMatch(w -> w.contains("jacoco.xml"));
		assertThat(result.coverageRecords()).isEmpty();
	}

	@Test
	void descriptorIsBasic() {
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.BASIC);
		assertThat(analyzer.applicability(new InMemoryWorkspace().add("a.ts", "x").context()).isApplicable()).isFalse();
	}

}
