package com.repointelligence.analysis.packs.java.maven;

import java.util.List;

/** The POMs of one snapshot, linked by local parent, plus a warning for each POM that could not be read. */
public record MavenReactor(List<MavenModule> modules, List<String> warnings) {

	public boolean isEmpty() {
		return modules.isEmpty();
	}

}
