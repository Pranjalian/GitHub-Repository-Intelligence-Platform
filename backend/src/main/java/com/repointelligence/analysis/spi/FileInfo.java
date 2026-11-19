package com.repointelligence.analysis.spi;

import com.repointelligence.repository.domain.FileType;

/**
 * One catalogued file of the snapshot, as seen by analyzers (a DTO, never the JPA entity). Only
 * {@link #extracted()} files have content in the workspace; binary and skipped files are metadata only.
 */
public record FileInfo(String path, String language, FileType fileType, long sizeBytes, String contentHash,
		boolean binary, String skippedReason) {

	public boolean extracted() {
		return !binary && skippedReason == null;
	}

}
