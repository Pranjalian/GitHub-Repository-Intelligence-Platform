package com.repointelligence.report.api;

import com.repointelligence.analysis.domain.CoverageScope;

/** Numbers straight from a coverage report; a counter the report did not contain is null, not 0. */
public record CoverageRecordDTO(String reportPath, CoverageScope scope, String name, Integer lineCovered,
		Integer lineMissed, Integer branchCovered, Integer branchMissed) {
}
