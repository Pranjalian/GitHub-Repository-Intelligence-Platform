package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.domain.AnalysisRequestedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts a run once the transaction that queued it has committed, so the run row is always visible
 * to the job. It is a separate bean from the publisher on purpose: {@code @Async} does not apply to
 * a call from within the same bean.
 */
@Component
@ConditionalOnProperty(name = "analysis.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AnalysisJobListener {

	private final AnalysisOrchestrator orchestrator;

	AnalysisJobListener(AnalysisOrchestrator orchestrator) {
		this.orchestrator = orchestrator;
	}

	@Async("analysisJobExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAnalysisRequested(AnalysisRequestedEvent event) {
		orchestrator.execute(event.runId());
	}

}
