package com.repointelligence.analysis.spi;

import com.repointelligence.common.model.ExecutionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What an analyzer returns: plain DTOs, no persistence types. Status is {@code COMPLETED} or
 * {@code COMPLETED_WITH_WARNINGS}; anything worse (failure, timeout, cancellation) is decided by the
 * orchestrator from how the analyzer ended.
 */
public record AnalyzerResult(ExecutionStatus status, List<FindingResult> findings,
		List<DependencyResult> dependencies, List<ModuleResult> modules, List<TestResult> testRecords,
		List<CoverageResult> coverageRecords, List<String> warnings, List<String> limitations,
		Map<String, Object> configSummary) {

	public AnalyzerResult {
		Objects.requireNonNull(status, "status");
		if (status != ExecutionStatus.COMPLETED && status != ExecutionStatus.COMPLETED_WITH_WARNINGS) {
			throw new IllegalArgumentException("An analyzer result is COMPLETED or COMPLETED_WITH_WARNINGS, not " + status);
		}
		findings = copy(findings);
		dependencies = copy(dependencies);
		modules = copy(modules);
		testRecords = copy(testRecords);
		coverageRecords = copy(coverageRecords);
		warnings = copy(warnings);
		limitations = copy(limitations);
		configSummary = configSummary == null ? Map.of() : Map.copyOf(configSummary);
	}

	private static <T> List<T> copy(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	public static Builder builder() {
		return new Builder();
	}

	/** An empty successful result. */
	public static AnalyzerResult empty() {
		return builder().build();
	}

	/** Collects results; the status becomes COMPLETED_WITH_WARNINGS as soon as a warning is added. */
	public static final class Builder {

		private final List<FindingResult> findings = new ArrayList<>();

		private final List<DependencyResult> dependencies = new ArrayList<>();

		private final List<ModuleResult> modules = new ArrayList<>();

		private final List<TestResult> testRecords = new ArrayList<>();

		private final List<CoverageResult> coverageRecords = new ArrayList<>();

		private final List<String> warnings = new ArrayList<>();

		private final List<String> limitations = new ArrayList<>();

		private Map<String, Object> configSummary = Map.of();

		public Builder finding(FindingResult finding) {
			findings.add(finding);
			return this;
		}

		public Builder dependency(DependencyResult dependency) {
			dependencies.add(dependency);
			return this;
		}

		public Builder module(ModuleResult module) {
			modules.add(module);
			return this;
		}

		public Builder testRecord(TestResult testRecord) {
			testRecords.add(testRecord);
			return this;
		}

		public Builder coverageRecord(CoverageResult coverageRecord) {
			coverageRecords.add(coverageRecord);
			return this;
		}

		public Builder warning(String warning) {
			warnings.add(warning);
			return this;
		}

		public Builder limitation(String limitation) {
			limitations.add(limitation);
			return this;
		}

		public Builder configSummary(Map<String, Object> summary) {
			this.configSummary = summary;
			return this;
		}

		public AnalyzerResult build() {
			ExecutionStatus status = warnings.isEmpty() ? ExecutionStatus.COMPLETED
					: ExecutionStatus.COMPLETED_WITH_WARNINGS;
			return new AnalyzerResult(status, findings, dependencies, modules, testRecords, coverageRecords, warnings,
					limitations, configSummary);
		}

	}

}
