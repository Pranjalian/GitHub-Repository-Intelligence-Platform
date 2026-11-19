package com.repointelligence.report.application;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure rules for turning per-analyzer execution statuses into one honest section status and the
 * severity counts of a section.
 */
public final class SectionAggregator {

	private static final Set<ExecutionStatus> ANALYZED = Set.of(ExecutionStatus.COMPLETED,
			ExecutionStatus.COMPLETED_WITH_WARNINGS);

	/** When nothing in a section completed, the most informative status wins, in this order. */
	private static final List<ExecutionStatus> PRIORITY = List.of(ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT,
			ExecutionStatus.CANCELLED, ExecutionStatus.UNAVAILABLE, ExecutionStatus.NOT_CONFIGURED,
			ExecutionStatus.NOT_SUPPORTED, ExecutionStatus.SKIPPED, ExecutionStatus.NOT_ANALYZED);

	private SectionAggregator() {
	}

	public static boolean isAnalyzed(ExecutionStatus status) {
		return ANALYZED.contains(status);
	}

	/**
	 * No executions: NOT_SUPPORTED (nothing exists for the category). All completed cleanly: COMPLETED.
	 * Some completed and others did not (or warned): COMPLETED_WITH_WARNINGS. None completed: the most
	 * significant failure status.
	 */
	public static ExecutionStatus aggregate(List<ExecutionStatus> statuses) {
		if (statuses.isEmpty()) {
			return ExecutionStatus.NOT_SUPPORTED;
		}
		long analyzed = statuses.stream().filter(SectionAggregator::isAnalyzed).count();
		if (analyzed == statuses.size()) {
			return statuses.stream().allMatch(s -> s == ExecutionStatus.COMPLETED) ? ExecutionStatus.COMPLETED
					: ExecutionStatus.COMPLETED_WITH_WARNINGS;
		}
		if (analyzed > 0) {
			return ExecutionStatus.COMPLETED_WITH_WARNINGS;
		}
		for (ExecutionStatus candidate : PRIORITY) {
			if (statuses.contains(candidate)) {
				return candidate;
			}
		}
		return ExecutionStatus.NOT_ANALYZED;
	}

	/** Null when the section was not analyzed; otherwise an entry for every severity (zeros are real zeros). */
	public static Map<Severity, Long> counts(ExecutionStatus sectionStatus, Map<Severity, Long> found) {
		if (!isAnalyzed(sectionStatus)) {
			return null;
		}
		Map<Severity, Long> counts = new EnumMap<>(Severity.class);
		for (Severity severity : Severity.values()) {
			counts.put(severity, found.getOrDefault(severity, 0L));
		}
		return counts;
	}

	public static String summary(AnalysisCategory category, ExecutionStatus status, Map<Severity, Long> counts) {
		String label = category.name().toLowerCase().replace('_', ' ');
		if (counts == null) {
			return switch (status) {
				case NOT_SUPPORTED -> "No analyzer exists for " + label + " in this repository.";
				case NOT_CONFIGURED -> "The " + label + " analyzer is not configured.";
				case UNAVAILABLE -> "The " + label + " analyzer's tool is unavailable.";
				case FAILED -> "The " + label + " analysis failed.";
				case TIMED_OUT -> "The " + label + " analysis ran out of time.";
				case CANCELLED -> "The " + label + " analysis was cancelled.";
				default -> "The " + label + " was not analyzed.";
			};
		}
		long total = counts.values().stream().mapToLong(Long::longValue).sum();
		String prefix = status == ExecutionStatus.COMPLETED_WITH_WARNINGS ? "Partly analyzed: " : "";
		return total == 0 ? prefix + "Analyzed; no " + label + " findings."
				: prefix + total + " " + label + " finding" + (total == 1 ? "" : "s") + ".";
	}

}
