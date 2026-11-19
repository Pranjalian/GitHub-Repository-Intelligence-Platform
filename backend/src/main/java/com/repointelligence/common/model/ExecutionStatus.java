package com.repointelligence.common.model;

/** Per-analyzer execution outcome; the honest status labels of Problem Statement section 12.3. */
public enum ExecutionStatus {
	COMPLETED, COMPLETED_WITH_WARNINGS, NOT_CONFIGURED, NOT_SUPPORTED, UNAVAILABLE, SKIPPED, FAILED, TIMED_OUT,
	CANCELLED, NOT_ANALYZED
}
