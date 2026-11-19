package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.MappingMethod;
import com.repointelligence.common.model.Confidence;
import java.util.List;
import java.util.Objects;

public record TestResult(String testFilePath, String testSymbol, Integer testMethodCount, String framework,
		String productionSymbol, MappingMethod mappingMethod, Confidence confidence, List<Evidence> evidence) {

	public TestResult {
		Objects.requireNonNull(testFilePath, "testFilePath");
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
	}

}
