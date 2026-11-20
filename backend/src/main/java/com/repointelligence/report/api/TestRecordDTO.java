package com.repointelligence.report.api;

import com.repointelligence.analysis.domain.MappingMethod;
import com.repointelligence.common.model.Confidence;

/** One test class (or test file). Counts and frameworks are null when the analyzer does not claim them. */
public record TestRecordDTO(String testFilePath, String testSymbol, Integer testMethodCount, String framework,
		String productionSymbol, MappingMethod mappingMethod, Confidence confidence) {
}
