package com.repointelligence.analysis.orchestration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** How analysis jobs are executed. */
@Validated
@ConfigurationProperties("analysis.jobs")
public record AnalysisJobProperties(
		/** When false, queued runs are not started automatically (maintenance, and tests of submission). */
		@DefaultValue("true") boolean enabled,
		/** Analysis jobs running at the same time. */
		@Min(1) @DefaultValue("2") int maxConcurrentJobs,
		/** Jobs waiting for a free worker; the submission cap keeps this far below the limit. */
		@Min(1) @DefaultValue("100") int queueCapacity,
		/** Time budget for one run, ingestion included. Analyzers still running at the deadline are stopped. */
		@NotNull @DefaultValue("PT15M") Duration maxAnalysisDuration) {
}
