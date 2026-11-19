package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.spi.AnalysisCancelledException;
import com.repointelligence.analysis.spi.CancellationSource;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.security.SecretRedactor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs tasks (analyzers, detectors) in isolation on the analyzer executor. One task failing, timing
 * out or being cancelled never affects the others. Results are handed over as each task completes,
 * and tasks still running at the deadline are stopped (interrupted and signalled through their
 * {@link CancellationToken}).
 */
@Component
public class IsolatedRunner {

	private static final Logger log = LoggerFactory.getLogger(IsolatedRunner.class);

	/** How often the wait loop checks for cancellation. */
	private static final long POLL_MILLIS = 100;

	private static final int MAX_ERROR_LENGTH = 300;

	private final ExecutorService executor;

	private final Clock clock;

	public IsolatedRunner(ExecutorService analyzerExecutor, Clock clock) {
		this.executor = analyzerExecutor;
		this.clock = clock;
	}

	@FunctionalInterface
	public interface Body<T> {

		T run(CancellationToken cancellation) throws Exception;

	}

	public record Task<T>(String id, Body<T> body) {
	}

	/** {@code status} is COMPLETED, FAILED, TIMED_OUT or CANCELLED; {@code value} only for COMPLETED. */
	public record Outcome<T>(String id, ExecutionStatus status, T value, String errorSummary, Instant startedAt,
			Instant completedAt) {

		public long durationMs() {
			return startedAt == null || completedAt == null ? 0 : Duration.between(startedAt, completedAt).toMillis();
		}

	}

	private record Submitted<T>(Task<T> task, Future<Outcome<T>> future, CancellationSource own, Instant submittedAt) {
	}

	/**
	 * Runs all tasks concurrently and calls {@code onOutcome} on the calling thread for each one as
	 * it finishes. Returns when every task has an outcome.
	 */
	public <T> List<Outcome<T>> runAll(List<Task<T>> tasks, Instant deadline, CancellationToken runCancellation,
			Consumer<Outcome<T>> onOutcome) {
		ExecutorCompletionService<Outcome<T>> completion = new ExecutorCompletionService<>(executor);
		Map<Future<Outcome<T>>, Submitted<T>> pending = new HashMap<>();
		Map<String, String> mdc = MDC.getCopyOfContextMap();
		for (Task<T> task : tasks) {
			CancellationSource own = new CancellationSource();
			CancellationToken token = () -> own.isCancelled() || runCancellation.isCancelled();
			Instant submitted = clock.instant();
			Future<Outcome<T>> future = completion.submit(() -> execute(task, token, mdc));
			pending.put(future, new Submitted<>(task, future, own, submitted));
		}

		List<Outcome<T>> outcomes = new ArrayList<>();
		while (!pending.isEmpty() && !runCancellation.isCancelled()) {
			long remaining = Duration.between(clock.instant(), deadline).toMillis();
			if (remaining <= 0) {
				break;
			}
			try {
				Future<Outcome<T>> done = completion.poll(Math.min(remaining, POLL_MILLIS), TimeUnit.MILLISECONDS);
				if (done != null) {
					Outcome<T> outcome = done.get();
					pending.remove(done);
					outcomes.add(outcome);
					onOutcome.accept(outcome);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (ExecutionException ex) {
				// execute() never throws; this would be a bug in the runner itself.
				throw new IllegalStateException("Task runner failed", ex.getCause());
			}
		}

		// Whatever is left ran out of time or was cancelled: stop it and say why.
		ExecutionStatus reason = runCancellation.isCancelled() ? ExecutionStatus.CANCELLED : ExecutionStatus.TIMED_OUT;
		for (Submitted<T> submitted : pending.values()) {
			Outcome<T> outcome = finishOrStop(submitted, reason);
			outcomes.add(outcome);
			onOutcome.accept(outcome);
		}
		return outcomes;
	}

	/** A task that finished just before being stopped keeps its real result. */
	private <T> Outcome<T> finishOrStop(Submitted<T> submitted, ExecutionStatus reason) {
		submitted.own().cancel();
		if (submitted.future().isDone() && !submitted.future().isCancelled()) {
			try {
				return submitted.future().get();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (ExecutionException ex) {
				// fall through to the stopped outcome
			}
		}
		submitted.future().cancel(true);
		Instant now = clock.instant();
		String message = reason == ExecutionStatus.TIMED_OUT ? "Stopped: the analysis time budget was exhausted."
				: "Stopped: the run was cancelled.";
		return new Outcome<>(submitted.task().id(), reason, null, message, submitted.submittedAt(), now);
	}

	private <T> Outcome<T> execute(Task<T> task, CancellationToken token, Map<String, String> parentMdc) {
		if (parentMdc != null) {
			MDC.setContextMap(parentMdc);
		}
		MDC.put("analyzerId", task.id());
		Instant started = clock.instant();
		try {
			T value = task.body().run(token);
			return new Outcome<>(task.id(), ExecutionStatus.COMPLETED, value, null, started, clock.instant());
		}
		catch (AnalysisCancelledException ex) {
			return new Outcome<>(task.id(), ExecutionStatus.CANCELLED, null, "Cancelled.", started, clock.instant());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Outcome<>(task.id(), ExecutionStatus.CANCELLED, null, "Interrupted.", started, clock.instant());
		}
		catch (Exception | StackOverflowError ex) {
			log.warn("{} failed", task.id(), ex);
			return new Outcome<>(task.id(), ExecutionStatus.FAILED, null, summarize(ex), started, clock.instant());
		}
		finally {
			MDC.clear();
		}
	}

	/** Class name plus a redacted, bounded message; never a stack trace. */
	static String summarize(Throwable ex) {
		String message = ex.getMessage() == null ? "" : ": " + SecretRedactor.redact(ex.getMessage());
		String text = ex.getClass().getSimpleName() + message;
		return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
	}

}
