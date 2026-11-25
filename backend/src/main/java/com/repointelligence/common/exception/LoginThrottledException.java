package com.repointelligence.common.exception;

/** Too many failed logins for a client address or username (429 with Retry-After). */
public class LoginThrottledException extends SubmissionRateLimitException {

	public LoginThrottledException(long retryAfterSeconds) {
		super("Too many failed sign-in attempts. Try again in " + retryAfterSeconds + " seconds.",
				retryAfterSeconds);
	}

}
