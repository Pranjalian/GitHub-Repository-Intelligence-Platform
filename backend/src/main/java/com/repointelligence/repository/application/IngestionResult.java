package com.repointelligence.repository.application;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Outcome of ingesting a snapshot. {@code workspace} is set only when the snapshot is ingested. For
 * {@code REJECTED} and {@code FAILED} the run and snapshot rows have already been updated.
 */
public record IngestionResult(Status status, UUID runId, UUID snapshotId, Path workspace, String failureReason) {

	public enum Status {

		INGESTED, REJECTED, FAILED

	}

	public boolean succeeded() {
		return status == Status.INGESTED;
	}

}
