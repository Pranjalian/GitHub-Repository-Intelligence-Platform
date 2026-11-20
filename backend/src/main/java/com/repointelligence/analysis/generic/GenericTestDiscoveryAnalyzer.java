package com.repointelligence.analysis.generic;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.TestAnalyzer;
import com.repointelligence.analysis.spi.TestResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.repository.domain.FileType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Counts test files by naming convention for stacks without a dedicated analyzer ({@code *.test.ts},
 * {@code *_test.go}, {@code test_*.py}, ...). It makes no claim about frameworks, test counts or coverage.
 * Java tests are handled by the Java test analyzer.
 */
@Component
public class GenericTestDiscoveryAnalyzer implements TestAnalyzer {

	public static final String ID = "generic-test-discovery-analyzer";

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Generic Test Discovery", "1.0.0", AnalysisCategory.TESTS, CapabilityLevel.GENERIC)
		.description("Counts test files by file-naming convention for any stack. No framework, test-count or coverage claims.")
		.limitations("Only file names and locations are used; test methods and frameworks are not identified.",
				"Tests are not executed and coverage is not measured.")
		.build();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		return testFiles(context).isEmpty() ? Applicability.notApplicable() : Applicability.applicable();
	}

	private static List<FileInfo> testFiles(AnalysisContext context) {
		return context.files()
			.stream()
			.filter(FileInfo::extracted)
			.filter(f -> f.fileType() == FileType.TEST && !f.path().endsWith(".java"))
			.toList();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		Map<String, Integer> byLanguage = new LinkedHashMap<>();
		for (FileInfo file : testFiles(context)) {
			result.testRecord(new TestResult(file.path(), null, null, null, null, null, null,
					List.of(new Evidence(file.path(), null, "test file by naming convention"))));
			byLanguage.merge(file.language() == null ? "unknown" : file.language(), 1, Integer::sum);
		}
		result.configSummary(Map.of("testFiles", byLanguage.values().stream().mapToInt(Integer::intValue).sum(), "byLanguage",
				byLanguage));
		DESCRIPTOR.limitations().forEach(result::limitation);
		return result.build();
	}

}
