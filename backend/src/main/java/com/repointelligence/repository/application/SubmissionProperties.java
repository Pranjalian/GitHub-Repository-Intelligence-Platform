package com.repointelligence.repository.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Protection of the submission endpoint: a rate limit and a cap on queued + running jobs. */
@Validated
@ConfigurationProperties("repository.submission")
public record SubmissionProperties(@Min(1) @DefaultValue("10") int maxPendingJobs,
		@Valid @DefaultValue RateLimit rateLimit) {

	/** Token bucket: {@code capacity} submissions, refilled evenly over {@code refillPeriod}. */
	public record RateLimit(@Min(1) @DefaultValue("10") int capacity,
			@NotNull @DefaultValue("PT1M") Duration refillPeriod) {
	}

}
