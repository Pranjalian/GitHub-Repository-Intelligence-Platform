package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** The request needs a valid session and has none (401). */
public class AuthenticationRequiredException extends ApiException {

	public AuthenticationRequiredException() {
		super(HttpStatus.UNAUTHORIZED, "Authentication is required. Sign in to continue.");
	}

}
