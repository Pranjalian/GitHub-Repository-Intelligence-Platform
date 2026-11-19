package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.CoverageScope;
import java.util.Objects;

/** Coverage numbers read from a real report. Never estimated; absent numbers stay null, not zero. */
public record CoverageResult(String reportPath, CoverageScope scope, String name, Integer lineCovered,
		Integer lineMissed, Integer branchCovered, Integer branchMissed) {

	public CoverageResult {
		Objects.requireNonNull(reportPath, "reportPath");
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(name, "name");
	}

}
