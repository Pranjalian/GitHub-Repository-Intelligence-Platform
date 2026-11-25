package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** A client-side rate limit was exceeded (429 with Retry-After), for example too many analysis submissions. */
public class SubmissionRateLimitException extends ApiException {

	private final long retryAfterSeconds;

	public SubmissionRateLimitException(long retryAfterSeconds) {
		this("Too many analysis submissions. Try again in " + retryAfterSeconds + " seconds.", retryAfterSeconds);
	}

	protected SubmissionRateLimitException(String message, long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, message);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

}
