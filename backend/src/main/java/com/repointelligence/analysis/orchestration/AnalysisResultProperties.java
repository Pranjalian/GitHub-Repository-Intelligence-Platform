package com.repointelligence.analysis.orchestration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Bounds on what an analyzer may store, so one noisy analyzer cannot flood the database. */
@Validated
@ConfigurationProperties("analysis.results")
public record AnalysisResultProperties(
		/** Longest string kept in a finding's evidence; longer text is truncated. */
		@Min(20) @DefaultValue("500") int maxExcerptLength,
		/** Findings stored per analyzer; the rest are dropped and the execution says so. */
		@Min(1) @DefaultValue("5000") int maxFindingsPerAnalyzer) {
}
