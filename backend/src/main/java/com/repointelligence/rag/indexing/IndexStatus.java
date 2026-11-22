package com.repointelligence.rag.indexing;

/** State of a snapshot's index job. {@link #NOT_INDEXED} is never stored; it means "no row". */
public enum IndexStatus {

	NOT_INDEXED, QUEUED, INDEXING, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED;

	public boolean inFlight() {
		return this == QUEUED || this == INDEXING;
	}

	public boolean searchable() {
		return this == COMPLETED || this == COMPLETED_WITH_WARNINGS;
	}

}
