package com.repointelligence.analysis.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.spi.AnalysisCancelledException;
import com.repointelligence.analysis.spi.CancellationSource;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.model.Severity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;

/** Pure logic of the orchestration layer: status rules, merging, fingerprints, isolation. */
class OrchestrationUnitTests {

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final IsolatedRunner runner = new IsolatedRunner(executor, Clock.systemUTC());

	@AfterEach
	void shutdown() {
		executor.shutdownNow();
		MDC.clear();
	}

	private static Instant inSeconds(long seconds) {
		return Instant.now().plusSeconds(seconds);
	}

	// ---- run status rules ----------------------------------------------------------------------

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "COMPLETED|0|false|COMPLETED", "COMPLETED,COMPLETED|0|false|COMPLETED",
			"COMPLETED,COMPLETED_WITH_WARNINGS|0|false|COMPLETED_WITH_WARNINGS",
			"COMPLETED,FAILED|0|false|COMPLETED_WITH_WARNINGS", "COMPLETED,TIMED_OUT|0|false|COMPLETED_WITH_WARNINGS",
			"COMPLETED,COMPLETED|1|false|COMPLETED_WITH_WARNINGS", "FAILED,FAILED|0|false|FAILED",
			"FAILED,TIMED_OUT|0|false|FAILED", "FAILED|3|false|FAILED", "COMPLETED,FAILED|0|true|CANCELLED",
			"|0|true|CANCELLED" })
	void runStatusFollowsTheRules(String ran, int unavailable, boolean cancelled, RunStatus expected) {
		List<ExecutionStatus> statuses = ran == null || ran.isBlank() ? List.of()
				: java.util.Arrays.stream(ran.split(",")).map(ExecutionStatus::valueOf).toList();

		assertThat(RunStatusCalculator.calculate(statuses, unavailable, cancelled)).isEqualTo(expected);
	}

	@Test
	void aRunWithNothingToRunCompletesCleanlyUnlessSomethingWasUnavailable() {
		assertThat(RunStatusCalculator.calculate(List.of(), 0, false)).isEqualTo(RunStatus.COMPLETED);
		assertThat(RunStatusCalculator.calculate(List.of(), 2, false)).isEqualTo(RunStatus.COMPLETED_WITH_WARNINGS);
	}

	// ---- technology merging --------------------------------------------------------------------

	private static DetectedTechnology tech(String name, Confidence confidence, String version, String detector,
			Evidence... evidence) {
		return new DetectedTechnology(name, TechnologyKind.LANGUAGE, version, confidence, List.of(evidence), detector,
				null);
	}

	@Test
	void mergingKeepsTheStrongestConfidenceCombinedEvidenceAndKnownVersion() {
		Evidence pom = new Evidence("pom.xml", 3, "maven.compiler.release");
		Evidence ext = new Evidence("A.java", null, "extension");

		List<DetectedTechnology> merged = TechnologyMerger.merge(List.of(
				tech("Java", Confidence.POSSIBLE, null, "extension-detector", ext),
				tech("java", Confidence.DETECTED, "25", "manifest-detector", pom, ext),
				new DetectedTechnology("Java", TechnologyKind.LANGUAGE, null, Confidence.LIKELY, List.of(), "x",
						new BigDecimal("87.50"))));

		assertThat(merged).hasSize(1);
		DetectedTechnology java = merged.get(0);
		assertThat(java.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(java.version()).isEqualTo("25");
		assertThat(java.evidence()).containsExactlyInAnyOrder(pom, ext);
		assertThat(java.detectorId()).isEqualTo("manifest-detector");
		assertThat(java.sharePercent()).isEqualByComparingTo("87.50");
	}

	@Test
	void mergingKeepsDifferentKindsAndNamesApart() {
		List<DetectedTechnology> merged = TechnologyMerger.merge(List.of(tech("Java", Confidence.DETECTED, null, "a"),
				new DetectedTechnology("Java", TechnologyKind.FRAMEWORK, null, Confidence.DETECTED, List.of(), "a", null),
				tech("Go", Confidence.DETECTED, null, "a")));

		assertThat(merged).hasSize(3);
	}

	// ---- fingerprints --------------------------------------------------------------------------

	private static FindingResult finding(String rule, String path, Integer start, Integer end) {
		return new FindingResult(AnalysisCategory.SECURITY, rule, Severity.HIGH, Confidence.LIKELY, "Title " + rule,
				null, path, null, start, end, null, AnalysisMethod.DETERMINISTIC_SCANNER, null);
	}

	@Test
	void theSameLocationReportedByDifferentAnalyzersSharesAFingerprint() {
		String secretScanner = AnalysisResultWriter.fingerprint("generic-secrets", finding("aws-key", "app.yml", 4, 4),
				"app.yml");
		String semgrep = AnalysisResultWriter.fingerprint("semgrep", finding("hardcoded-credential", "app.yml", 4, 4),
				"app.yml");

		assertThat(secretScanner).isEqualTo(semgrep).matches("^[0-9a-f]{64}$");
	}

	@Test
	void differentLocationsOrCategoriesHaveDifferentFingerprints() {
		String base = AnalysisResultWriter.fingerprint("a", finding("r", "app.yml", 4, 4), "app.yml");

		assertThat(AnalysisResultWriter.fingerprint("a", finding("r", "app.yml", 5, 5), "app.yml")).isNotEqualTo(base);
		assertThat(AnalysisResultWriter.fingerprint("a", finding("r", "other.yml", 4, 4), "other.yml"))
			.isNotEqualTo(base);
		FindingResult otherCategory = new FindingResult(AnalysisCategory.CODE_QUALITY, "r", Severity.HIGH,
				Confidence.LIKELY, "t", null, "app.yml", null, 4, 4, null, AnalysisMethod.PARSER, null);
		assertThat(AnalysisResultWriter.fingerprint("a", otherCategory, "app.yml")).isNotEqualTo(base);
	}

	@Test
	void findingsWithoutALocationAreFingerprintedByAnalyzerRuleAndTitle() {
		String one = AnalysisResultWriter.fingerprint("a", finding("r", null, null, null), null);
		String same = AnalysisResultWriter.fingerprint("a", finding("r", null, null, null), null);
		String otherAnalyzer = AnalysisResultWriter.fingerprint("b", finding("r", null, null, null), null);
		String otherRule = AnalysisResultWriter.fingerprint("a", finding("r2", null, null, null), null);

		assertThat(one).isEqualTo(same).isNotEqualTo(otherAnalyzer).isNotEqualTo(otherRule);
	}

	// ---- cancellation registry -----------------------------------------------------------------

	@Test
	void theRegistrySignalsOnlyRunsThatAreExecuting() {
		RunCancellationRegistry registry = new RunCancellationRegistry();
		UUID running = UUID.randomUUID();
		CancellationSource source = registry.register(running);

		assertThat(registry.isRunning(running)).isTrue();
		assertThat(registry.cancel(UUID.randomUUID())).isFalse();
		assertThat(source.isCancelled()).isFalse();
		assertThat(registry.cancel(running)).isTrue();
		assertThat(source.isCancelled()).isTrue();
		registry.remove(running);
		assertThat(registry.isRunning(running)).isFalse();
		assertThat(registry.cancel(running)).isFalse();
	}

	// ---- isolated runner -----------------------------------------------------------------------

	@Test
	void tasksRunConcurrentlyAndReportEachOutcomeAsItFinishes() {
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		List<IsolatedRunner.Task<String>> tasks = List.of(new IsolatedRunner.Task<>("slow", token -> {
			Thread.sleep(300);
			return "slow-result";
		}), new IsolatedRunner.Task<>("fast", token -> "fast-result"));

		List<IsolatedRunner.Outcome<String>> outcomes = runner.runAll(tasks, inSeconds(10), () -> false,
				outcome -> order.add(outcome.id()));

		assertThat(order).containsExactly("fast", "slow");
		assertThat(outcomes).extracting(IsolatedRunner.Outcome::status).containsOnly(ExecutionStatus.COMPLETED);
		assertThat(outcomes).extracting(IsolatedRunner.Outcome::value).containsExactlyInAnyOrder("fast-result",
				"slow-result");
		assertThat(outcomes.stream().filter(o -> o.id().equals("slow")).findFirst().orElseThrow().durationMs())
			.isGreaterThanOrEqualTo(250);
	}

	@Test
	void aFailingTaskIsIsolatedAndItsErrorIsRedactedAndBounded() {
		List<IsolatedRunner.Task<String>> tasks = List.of(new IsolatedRunner.Task<>("bad", token -> {
			throw new IllegalStateException("could not read config: password=hunter2 " + "x".repeat(1000));
		}), new IsolatedRunner.Task<>("good", token -> "ok"),
				new IsolatedRunner.Task<>("overflow", token -> {
					throw new StackOverflowError();
				}));

		List<IsolatedRunner.Outcome<String>> outcomes = runner.runAll(tasks, inSeconds(10), () -> false, o -> {
		});

		IsolatedRunner.Outcome<String> bad = byId(outcomes, "bad");
		assertThat(bad.status()).isEqualTo(ExecutionStatus.FAILED);
		assertThat(bad.errorSummary()).startsWith("IllegalStateException: could not read config: password=")
			.contains("***REDACTED***")
			.doesNotContain("hunter2")
			.hasSizeLessThanOrEqualTo(300)
			.doesNotContain("\tat ");
		assertThat(byId(outcomes, "good").status()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(byId(outcomes, "overflow").status()).isEqualTo(ExecutionStatus.FAILED);
	}

	@Test
	void aTaskThatOverrunsTheDeadlineIsStoppedAndMarkedTimedOut() throws Exception {
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicBoolean sawCancellation = new AtomicBoolean();
		CountDownLatch stopped = new CountDownLatch(1);
		List<IsolatedRunner.Task<String>> tasks = List.of(new IsolatedRunner.Task<>("slow", token -> {
			try {
				while (!token.isCancelled()) {
					Thread.sleep(20);
				}
				sawCancellation.set(true);
				return "late";
			}
			catch (InterruptedException ex) {
				interrupted.set(true);
				throw ex;
			}
			finally {
				stopped.countDown();
			}
		}), new IsolatedRunner.Task<>("fast", token -> "done"));
		long start = System.currentTimeMillis();

		List<IsolatedRunner.Outcome<String>> outcomes = runner.runAll(tasks,
				Instant.now().plusMillis(400), () -> false, o -> {
				});

		assertThat(System.currentTimeMillis() - start).isLessThan(3000);
		assertThat(byId(outcomes, "slow").status()).isEqualTo(ExecutionStatus.TIMED_OUT);
		assertThat(byId(outcomes, "slow").errorSummary()).contains("time budget");
		assertThat(byId(outcomes, "fast").status()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(stopped.await(2, TimeUnit.SECONDS)).as("the slow task was actually stopped").isTrue();
		assertThat(interrupted.get() || sawCancellation.get()).isTrue();
	}

	@Test
	void cancellingTheRunStopsUnfinishedTasksButKeepsFinishedOnes() {
		CancellationSource cancel = new CancellationSource();
		List<IsolatedRunner.Task<String>> tasks = List.of(new IsolatedRunner.Task<>("blocked", token -> {
			Thread.sleep(30_000);
			return "never";
		}), new IsolatedRunner.Task<>("quick", token -> {
			return "quick-result";
		}));

		List<IsolatedRunner.Outcome<String>> outcomes = runner.runAll(tasks, inSeconds(60), cancel, outcome -> {
			if (outcome.id().equals("quick")) {
				cancel.cancel();
			}
		});

		assertThat(byId(outcomes, "quick").status()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(byId(outcomes, "blocked").status()).isEqualTo(ExecutionStatus.CANCELLED);
		assertThat(byId(outcomes, "blocked").errorSummary()).contains("cancelled");
	}

	@Test
	void aTaskThatThrowsCancellationIsReportedAsCancelledNotFailed() {
		List<IsolatedRunner.Task<String>> tasks = List.of(new IsolatedRunner.Task<>("polite", token -> {
			throw new AnalysisCancelledException();
		}));

		List<IsolatedRunner.Outcome<String>> outcomes = runner.runAll(tasks, inSeconds(10), () -> false, o -> {
		});

		assertThat(outcomes.get(0).status()).isEqualTo(ExecutionStatus.CANCELLED);
	}

	@Test
	void taskThreadsInheritTheLoggingContextAndAddTheirAnalyzerId() {
		MDC.put("runId", "run-123");
		AtomicReference<Map<String, String>> seen = new AtomicReference<>();
		List<IsolatedRunner.Task<String>> tasks = List.of(new IsolatedRunner.Task<>("pmd-java", token -> {
			seen.set(MDC.getCopyOfContextMap());
			return "ok";
		}));

		runner.runAll(tasks, inSeconds(10), () -> false, o -> {
		});

		assertThat(seen.get()).containsEntry("runId", "run-123").containsEntry("analyzerId", "pmd-java");
	}

	@Test
	void noTasksReturnsImmediately() {
		assertThat(runner.<String>runAll(List.of(), inSeconds(10), () -> false, o -> {
		})).isEmpty();
	}

	private static <T> IsolatedRunner.Outcome<T> byId(List<IsolatedRunner.Outcome<T>> outcomes, String id) {
		return outcomes.stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
	}

}
