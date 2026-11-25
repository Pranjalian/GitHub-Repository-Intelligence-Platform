package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Login failed (401). One message for every cause (unknown user, wrong password): the response must not reveal
 * which part was wrong.
 */
public class InvalidCredentialsException extends ApiException {

	public static final String MESSAGE = "Invalid username or password";

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED, MESSAGE);
	}

}
