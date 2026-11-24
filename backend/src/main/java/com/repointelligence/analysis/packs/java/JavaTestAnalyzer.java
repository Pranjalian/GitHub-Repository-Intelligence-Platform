package com.repointelligence.analysis.packs.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.MappingMethod;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CoverageResult;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.TestAnalyzer;
import com.repointelligence.analysis.spi.TestReferenceResult;
import com.repointelligence.analysis.spi.TestResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.FileType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds Java tests and real coverage. Test classes and methods are counted from the syntax tree (so
 * annotations in comments or strings never count), the framework comes from imports, and tests are mapped
 * to production classes by naming convention only. Coverage comes only from a committed JaCoCo report:
 * tests are never executed, and without a report no number is shown, never 0%.
 */
@Component
public class JavaTestAnalyzer implements TestAnalyzer {

	public static final String ID = "java-test-analyzer";

	public static final String COVERAGE_UNAVAILABLE = "Coverage unavailable: no JaCoCo report found in the repository. "
			+ "Tests are not executed in MVP 1.";

	private static final Logger log = LoggerFactory.getLogger(JavaTestAnalyzer.class);

	private static final long MAX_JAVA_BYTES = 256 * 1024L;

	private static final long MAX_REPORT_BYTES = 20L * 1024 * 1024;

	private static final Pattern TEST_CLASS_SUFFIX = Pattern.compile("^(.+?)(?:Tests?|IT|ITCase|TestCase)$");

	private static final Pattern TEST_CLASS_PREFIX = Pattern.compile("^Test(.+)$");

	private static final int MAX_REFERENCES = 20_000;

	private static final Pattern REPORT_PATH = Pattern.compile("(?:^|.*/)jacoco[^/]*\\.xml$|.*/jacoco/[^/]*\\.xml$");

	private static final Set<String> ALWAYS_JUNIT5 = Set.of("ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Java Test Analyzer", "1.1.0", AnalysisCategory.TESTS, CapabilityLevel.BASIC)
		.languages("Java")
		.description("Counts test classes and methods (JUnit 4/5, TestNG) from the syntax tree, maps tests to "
				+ "production classes by naming convention, records which production types and methods each test "
				+ "names, and reads coverage from a committed JaCoCo report.")
		.limitations("Tests are not executed; there is no measured pass/fail result.",
				"The test-to-code mapping shown in the report uses naming conventions only (FooTest, FooTests, FooIT map to Foo).",
				"Test references (used by impact analysis) are read from type names, fields, variables, constructor "
						+ "calls and static calls in the test; calls on chained expressions (for example MockMvc requests) "
						+ "have no known receiver type and are not linked.",
				"Coverage appears only when a JaCoCo report is committed to the repository, and such a report may be "
						+ "stale relative to the analysed commit.")
		.build();

	private record PendingTest(String path, String packageName, String simpleName, int count, String framework, int line,
			CompilationUnit unit, TypeDeclaration<?> type) {
	}

	private record TypeInfo(String packageName, String simpleName, String path, int line) {

		String qualified() {
			return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
		}

	}

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		boolean java = context.files().stream().anyMatch(f -> f.extracted() && f.path().endsWith(".java"));
		return java ? Applicability.applicable() : Applicability.notApplicable();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
			.setAttributeComments(false));

		List<TypeInfo> production = new ArrayList<>();
		List<TestResult> tests = new ArrayList<>();
		Map<String, int[]> byFramework = new LinkedHashMap<>(); // framework -> {classes, methods}
		Map<String, TypeInfo> productionByQualified = new HashMap<>();
		List<PendingTest> pendingTests = new ArrayList<>();
		Set<String> productionTypeNames = new java.util.TreeSet<>();

		for (FileInfo file : context.files()) {
			if (!file.extracted() || !file.path().endsWith(".java")) {
				continue;
			}
			context.cancellation().throwIfCancelled();
			Optional<CompilationUnit> unit = parse(context, parser, file, result);
			if (unit.isEmpty()) {
				continue;
			}
			String packageName = unit.get().getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
			boolean testFile = file.fileType() == FileType.TEST;
			for (TypeDeclaration<?> type : unit.get().getTypes()) {
				if (testFile) {
					Map<String, Integer> counts = countTests(unit.get(), type);
					int total = counts.values().stream().mapToInt(Integer::intValue).sum();
					if (total > 0) {
						String framework = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
						pendingTests.add(new PendingTest(file.path(), packageName, type.getNameAsString(), total, framework,
								type.getBegin().map(p -> p.line).orElse(1), unit.get(), type));
						byFramework.computeIfAbsent(framework, k -> new int[2])[0]++;
						byFramework.get(framework)[1] += total;
					}
				}
				else {
					collectTypeNames(type, packageName, productionTypeNames);
				}
				if (!testFile && type instanceof ClassOrInterfaceDeclaration cls && !cls.isInterface()) {
					TypeInfo info = new TypeInfo(packageName, type.getNameAsString(), file.path(),
							type.getBegin().map(p -> p.line).orElse(1));
					production.add(info);
					productionByQualified.put(info.qualified(), info);
				}
			}
		}

		Set<String> testedProduction = new java.util.HashSet<>();
		TestReferenceExtractor extractor = new TestReferenceExtractor(productionTypeNames);
		int referenceCount = 0;
		boolean referencesCapped = false;
		for (PendingTest pending : pendingTests) {
			context.cancellation().throwIfCancelled();
			String target = productionNameFor(pending.simpleName());
			TypeInfo mapped = target == null ? null
					: productionByQualified.get(pending.packageName().isEmpty() ? target : pending.packageName() + "." + target);
			if (mapped != null) {
				testedProduction.add(mapped.qualified());
			}
			String qualifiedTest = pending.packageName().isEmpty() ? pending.simpleName()
					: pending.packageName() + "." + pending.simpleName();
			List<TestReferenceResult> references = extractor.extract(pending.unit(), pending.type(), pending.packageName());
			if (referenceCount + references.size() > MAX_REFERENCES) {
				references = references.subList(0, Math.max(0, MAX_REFERENCES - referenceCount));
				referencesCapped = true;
			}
			referenceCount += references.size();
			tests.add(new TestResult(pending.path(), qualifiedTest, pending.count(), pending.framework(),
					mapped == null ? null : mapped.qualified(), mapped == null ? null : MappingMethod.NAMING_CONVENTION,
					mapped == null ? null : Confidence.LIKELY,
					List.of(new Evidence(pending.path(), pending.line(), "test class " + pending.simpleName())), references));
		}
		if (referencesCapped) {
			result.warning("Test references were capped at " + MAX_REFERENCES + "; impact analysis may miss some tests.");
		}
		tests.forEach(result::testRecord);

		for (TypeInfo info : production) {
			if (!testedProduction.contains(info.qualified())) {
				Map<String, Object> evidence = new LinkedHashMap<>();
				evidence.put("class", info.qualified());
				evidence.put("convention", info.simpleName() + "Test | " + info.simpleName() + "Tests | " + info.simpleName() + "IT");
				result.finding(new FindingResult(AnalysisCategory.TESTS, "no-test-found", Severity.INFO, Confidence.POSSIBLE,
						"No test found by naming convention for " + info.simpleName(),
						info.qualified() + " has no test class named " + info.simpleName() + "Test, " + info.simpleName()
								+ "Tests or " + info.simpleName() + "IT in the same package. It may still be tested under another name.",
						info.path(), null, info.line(), info.line(), evidence, AnalysisMethod.HEURISTIC,
						"Check whether this class is covered by a differently named test."));
			}
		}

		readCoverage(context, result);

		Map<String, Object> summary = new LinkedHashMap<>();
		Map<String, Object> frameworks = new LinkedHashMap<>();
		byFramework.forEach((framework, counts) -> frameworks.put(framework, Map.of("classes", counts[0], "methods", counts[1])));
		summary.put("testClasses", tests.size());
		summary.put("testMethods", tests.stream().mapToInt(t -> t.testMethodCount() == null ? 0 : t.testMethodCount()).sum());
		summary.put("frameworks", frameworks);
		summary.put("productionClasses", production.size());
		summary.put("testReferences", referenceCount);
		result.configSummary(summary);
		DESCRIPTOR.limitations().forEach(result::limitation);
		return result.build();
	}

	/** Fully qualified names of a type and its member types (nested types are named Outer.Inner, like code_symbol). */
	private static void collectTypeNames(TypeDeclaration<?> type, String packageName, Set<String> names) {
		collectQualified(type, packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString(),
				names);
	}

	private static void collectQualified(TypeDeclaration<?> type, String qualified, Set<String> names) {
		names.add(qualified);
		for (var member : type.getMembers()) {
			if (member instanceof TypeDeclaration<?> nested) {
				collectQualified(nested, qualified + "." + nested.getNameAsString(), names);
			}
		}
	}

	private Optional<CompilationUnit> parse(AnalysisContext context, JavaParser parser, FileInfo file, AnalyzerResult.Builder result) {
		if (file.sizeBytes() > MAX_JAVA_BYTES) {
			result.warning(file.path() + ": skipped, larger than " + MAX_JAVA_BYTES / 1024 + " KB.");
			return Optional.empty();
		}
		try {
			var parsed = parser.parse(context.workspace().readString(file.path(), MAX_JAVA_BYTES));
			if (parsed.getResult().isEmpty() || !parsed.isSuccessful()) {
				result.warning(file.path() + ": could not be parsed; its tests or classes are missing from the inventory.");
				return parsed.getResult().filter(unit -> true);
			}
			return parsed.getResult();
		}
		catch (IOException | RuntimeException ex) {
			result.warning(file.path() + ": could not be read.");
			return Optional.empty();
		}
	}

	/** Test methods per framework in one top-level type (nested types included). Comments and strings never count. */
	private static Map<String, Integer> countTests(CompilationUnit unit, TypeDeclaration<?> type) {
		List<String> imports = unit.getImports().stream().map(ImportDeclaration::getNameAsString).toList();
		List<Boolean> wildcard = unit.getImports().stream().map(ImportDeclaration::isAsterisk).toList();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (MethodDeclaration method : type.findAll(MethodDeclaration.class)) {
			for (AnnotationExpr annotation : method.getAnnotations()) {
				String framework = frameworkOf(annotation.getNameAsString(), imports, wildcard);
				if (framework != null) {
					counts.merge(framework, 1, Integer::sum);
					break; // one test per method
				}
			}
		}
		return counts;
	}

	static String frameworkOf(String annotation, List<String> imports, List<Boolean> wildcard) {
		String simple = annotation.substring(annotation.lastIndexOf('.') + 1);
		if (annotation.startsWith("org.junit.jupiter.")) {
			return ALWAYS_JUNIT5.contains(simple) || simple.equals("Test") ? "JUnit 5" : null;
		}
		if (annotation.equals("org.junit.Test")) {
			return "JUnit 4";
		}
		if (annotation.startsWith("org.testng.annotations.") && simple.equals("Test")) {
			return "TestNG";
		}
		if (ALWAYS_JUNIT5.contains(simple)) {
			return "JUnit 5";
		}
		if (!simple.equals("Test") || annotation.contains(".")) {
			return null;
		}
		for (int i = 0; i < imports.size(); i++) {
			String imported = imports.get(i);
			boolean star = wildcard.get(i);
			if (imported.equals("org.junit.jupiter.api.Test") || (star && imported.equals("org.junit.jupiter.api"))) {
				return "JUnit 5";
			}
			if (imported.equals("org.junit.Test") || (star && imported.equals("org.junit"))) {
				return "JUnit 4";
			}
			if (imported.equals("org.testng.annotations.Test") || (star && imported.equals("org.testng.annotations"))) {
				return "TestNG";
			}
		}
		return null;
	}

	/** FooTest, FooTests, FooIT, FooITCase, FooTestCase, TestFoo map to Foo. */
	static String productionNameFor(String testClass) {
		Matcher suffix = TEST_CLASS_SUFFIX.matcher(testClass);
		if (suffix.matches()) {
			return suffix.group(1);
		}
		Matcher prefix = TEST_CLASS_PREFIX.matcher(testClass);
		return prefix.matches() ? prefix.group(1) : null;
	}

	private void readCoverage(AnalysisContext context, AnalyzerResult.Builder result) {
		List<String> reports = context.files()
			.stream()
			.filter(FileInfo::extracted)
			.map(FileInfo::path)
			.filter(path -> path.endsWith(".xml") && REPORT_PATH.matcher(path).matches())
			.sorted()
			.toList();
		int read = 0;
		for (String report : reports) {
			try (InputStream in = context.workspace().open(report)) {
				byte[] bytes = in.readNBytes((int) MAX_REPORT_BYTES + 1);
				if (bytes.length > MAX_REPORT_BYTES) {
					result.warning(report + ": skipped, larger than 20 MB.");
					continue;
				}
				List<CoverageResult> records = JacocoReportParser.parse(report, new java.io.ByteArrayInputStream(bytes));
				records.forEach(result::coverageRecord);
				read++;
				result.limitation("Coverage in " + report + " comes from a report committed to the repository; it may be stale "
						+ "relative to the analysed commit.");
			}
			catch (Exception ex) {
				log.debug("Unreadable JaCoCo report {}: {}", report, ex.getClass().getSimpleName());
				result.warning(report + ": is not a readable JaCoCo report (" + ex.getClass().getSimpleName() + "); no coverage is shown from it.");
			}
		}
		if (read == 0) {
			result.limitation(COVERAGE_UNAVAILABLE);
		}
	}

}
