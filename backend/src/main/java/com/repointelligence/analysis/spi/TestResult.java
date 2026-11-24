package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.MappingMethod;
import com.repointelligence.common.model.Confidence;
import java.util.List;
import java.util.Objects;

/**
 * A test class (or test file) with its naming-convention mapping and, where the analyzer can read them,
 * the production types and methods it refers to.
 */
public record TestResult(String testFilePath, String testSymbol, Integer testMethodCount, String framework,
		String productionSymbol, MappingMethod mappingMethod, Confidence confidence, List<Evidence> evidence,
		List<TestReferenceResult> references) {

	public TestResult {
		Objects.requireNonNull(testFilePath, "testFilePath");
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
		references = references == null ? List.of() : List.copyOf(references);
	}

	public TestResult(String testFilePath, String testSymbol, Integer testMethodCount, String framework,
			String productionSymbol, MappingMethod mappingMethod, Confidence confidence, List<Evidence> evidence) {
		this(testFilePath, testSymbol, testMethodCount, framework, productionSymbol, mappingMethod, confidence, evidence,
				List.of());
	}

}
