package com.repointelligence.report.api;

import java.util.List;
import java.util.Map;

/**
 * Test inventory of a snapshot. {@code testMethods} and the framework counts are null when no analyzer
 * counted tests; {@code untestedClasses} are classes with no test found by naming convention (a hint, not a verdict).
 */
public record TestsReportDTO(Integer testClasses, Integer testMethods, Map<String, Integer> methodsByFramework,
		List<TestRecordDTO> records, List<String> untestedClasses) {
}
