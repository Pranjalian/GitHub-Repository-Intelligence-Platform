package com.repointelligence.repository.infrastructure.storage;

/**
 * One catalogued archive entry. {@code skippedReason} is set for entries that were recorded but not
 * extracted (links, oversized files, unsafe names, ...). Binary files are hashed but not written.
 */
public record ExtractedEntry(String path, long sizeBytes, String sha256, boolean binary, String skippedReason) {

	/** True when the content exists in the workspace and may be read by analyzers. */
	public boolean extracted() {
		return skippedReason == null && !binary;
	}

}
