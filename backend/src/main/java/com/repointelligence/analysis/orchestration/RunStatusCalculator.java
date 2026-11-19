package com.repointelligence.analysis.orchestration;

import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import java.util.Collection;

/**
 * Derives the run status from how its analyzers ended. Pure logic so the rules can be tested
 * exhaustively.
 *
 * <ul>
 * <li>cancelled: {@code CANCELLED}
 * <li>every analyzer that was meant to run completed cleanly and nothing was unavailable or failed:
 * {@code COMPLETED}
 * <li>at least one analyzer succeeded, but something failed, timed out, was unavailable or finished
 * with warnings: {@code COMPLETED_WITH_WARNINGS}
 * <li>analyzers were meant to run and none succeeded: {@code FAILED}
 * </ul>
 */
final class RunStatusCalculator {

	private RunStatusCalculator() {
	}

	/**
	 * @param ran final status of every detector and analyzer that was scheduled to run
	 * @param unavailable how many analyzers could not run (a required tool is missing, or the
	 * applicability check failed); a warning, but not a failure of the run
	 */
	static RunStatus calculate(Collection<ExecutionStatus> ran, int unavailable, boolean cancelled) {
		if (cancelled) {
			return RunStatus.CANCELLED;
		}
		long succeeded = ran.stream().filter(RunStatusCalculator::succeeded).count();
		if (!ran.isEmpty() && succeeded == 0) {
			return RunStatus.FAILED;
		}
		boolean clean = ran.stream().allMatch(status -> status == ExecutionStatus.COMPLETED);
		return clean && unavailable == 0 ? RunStatus.COMPLETED : RunStatus.COMPLETED_WITH_WARNINGS;
	}

	private static boolean succeeded(ExecutionStatus status) {
		return status == ExecutionStatus.COMPLETED || status == ExecutionStatus.COMPLETED_WITH_WARNINGS;
	}

}
