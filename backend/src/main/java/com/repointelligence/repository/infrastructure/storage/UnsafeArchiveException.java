package com.repointelligence.repository.infrastructure.storage;

import com.repointelligence.common.exception.AnalysisLimitException;

/**
 * The archive is malicious or malformed in a way that is never legitimate for a GitHub tarball
 * (path traversal, absolute paths, unexpected layout). The repository is rejected.
 */
public class UnsafeArchiveException extends AnalysisLimitException {

	public UnsafeArchiveException(String message) {
		super(message);
	}

}
