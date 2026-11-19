package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** The request is valid but conflicts with the current state (409), e.g. cancelling a finished run. */
public class ConflictException extends ApiException {

	public ConflictException(String message) {
		super(HttpStatus.CONFLICT, message);
	}

}
