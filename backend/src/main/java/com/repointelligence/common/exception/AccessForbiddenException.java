package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** The request was understood but refused (403), for example a state-changing call without a valid CSRF token. */
public class AccessForbiddenException extends ApiException {

	public AccessForbiddenException(String message) {
		super(HttpStatus.FORBIDDEN, message);
	}

}
