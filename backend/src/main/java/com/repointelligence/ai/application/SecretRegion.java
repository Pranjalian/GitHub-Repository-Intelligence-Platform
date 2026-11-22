package com.repointelligence.ai.application;

/** Lines of a file where a scanner reported a possible secret; never sent to a model. */
public record SecretRegion(String filePath, int startLine, int endLine) {

	public boolean covers(String path, int line) {
		return filePath.equals(path) && line >= startLine && line <= endLine;
	}

}
