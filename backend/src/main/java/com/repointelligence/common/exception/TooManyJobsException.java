package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

public class TooManyJobsException extends ApiException {

	public TooManyJobsException(String message) {
		super(HttpStatus.TOO_MANY_REQUESTS, message);
	}

}
