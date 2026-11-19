package com.repointelligence.analysis.domain;

import java.util.UUID;

/**
 * Published inside the submission transaction when a new run is queued. The async pipeline listens
 * with {@code @TransactionalEventListener(AFTER_COMMIT)} so the run row is always committed first.
 */
public record AnalysisRequestedEvent(UUID runId) {
}
