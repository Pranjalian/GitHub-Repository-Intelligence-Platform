package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** The request is malformed in a way bean validation cannot express (400), e.g. an unknown sort key. */
public class BadRequestException extends ApiException {

	public BadRequestException(String message) {
		super(HttpStatus.BAD_REQUEST, message);
	}

}
