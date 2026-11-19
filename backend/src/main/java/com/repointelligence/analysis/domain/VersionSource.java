package com.repointelligence.analysis.domain;

/** How a dependency version was determined. */
public enum VersionSource {
	DECLARED, PROPERTY, MANAGED_LOCAL, MANAGED_REMOTE_UNRESOLVED, UNRESOLVED
}
