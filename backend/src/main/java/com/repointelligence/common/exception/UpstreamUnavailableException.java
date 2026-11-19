package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** An upstream dependency (e.g. GitHub) failed or is unavailable. 502 by default. */
public class UpstreamUnavailableException extends ApiException {

	public UpstreamUnavailableException(String message) {
		super(HttpStatus.BAD_GATEWAY, message);
	}

	public UpstreamUnavailableException(HttpStatus status, String message) {
		super(status, message);
	}

}
