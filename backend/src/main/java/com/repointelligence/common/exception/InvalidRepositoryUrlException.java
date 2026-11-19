package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidRepositoryUrlException extends ApiException {

	public InvalidRepositoryUrlException(String message) {
		super(HttpStatus.BAD_REQUEST, message);
	}

}
