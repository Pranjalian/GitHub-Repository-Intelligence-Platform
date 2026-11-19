package com.repointelligence.common.exception;

import org.springframework.http.HttpStatus;

/** A configured repository or analysis limit was exceeded (size, file count, ...). */
public class AnalysisLimitException extends ApiException {

	public AnalysisLimitException(String message) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, message);
	}

}
