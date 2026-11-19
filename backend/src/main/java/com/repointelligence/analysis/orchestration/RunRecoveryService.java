package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.storage.WorkspaceSweeper;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs left queued, ingesting or analyzing by a previous process can never finish: at startup they
 * are marked failed ("interrupted by restart") and their workspaces swept, so nothing stays
 * "running" forever and the job cap is not eaten by ghosts.
 */
@Component
public class RunRecoveryService {

	static final String REASON = "Interrupted by restart.";

	private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);

	private static final Set<RunStatus> IN_FLIGHT = Set.of(RunStatus.QUEUED, RunStatus.INGESTING, RunStatus.ANALYZING);

	private final AnalysisRunRepository runs;

	private final AnalyzerExecutionRepository executions;

	private final RepositorySnapshotRepository snapshots;

	private final WorkspaceSweeper sweeper;

	private final Clock clock;

	private final TransactionTemplate transaction;

	public RunRecoveryService(AnalysisRunRepository runs, AnalyzerExecutionRepository executions,
			RepositorySnapshotRepository snapshots, WorkspaceSweeper sweeper, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.runs = runs;
		this.executions = executions;
		this.snapshots = snapshots;
		this.sweeper = sweeper;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recoverOnStartup() {
		recover();
	}

	/** @return the number of runs that were marked failed */
	public int recover() {
		Integer recovered = transaction.execute(status -> {
			// Order matters: executions and snapshots are selected through the runs that are still in flight.
			executions.failPendingOfRuns(IN_FLIGHT, REASON);
			snapshots.failInterruptedIngestions(REASON);
			return runs.failInFlight(IN_FLIGHT, REASON, clock.instant());
		});
		int count = recovered == null ? 0 : recovered;
		if (count > 0) {
			log.warn("Marked {} interrupted analysis run(s) as failed", count);
		}
		sweeper.sweepOrphans();
		return count;
	}

}
