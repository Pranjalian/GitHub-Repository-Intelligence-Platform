package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** The caller submitted too many analyses in a short time (429 with Retry-After). */
public class SubmissionRateLimitException extends ApiException {

	private final long retryAfterSeconds;

	public SubmissionRateLimitException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS,
				"Too many analysis submissions. Try again in " + retryAfterSeconds + " seconds.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

}
