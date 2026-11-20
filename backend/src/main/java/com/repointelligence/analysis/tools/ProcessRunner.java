package com.repointelligence.analysis.tools;

import com.repointelligence.analysis.spi.AnalysisCancelledException;
import com.repointelligence.analysis.spi.CancellationToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs an external tool as a child process with a minimal environment and size-capped output, and kills
 * the whole process tree when the run is cancelled or its time is up. Nothing from the application's own
 * environment (tokens, passwords) is passed to the tool.
 */
public final class ProcessRunner {

	private static final long POLL_MILLIS = 100;

	private ProcessRunner() {
	}

	/** {@code truncated} is true when the tool wrote more than the caps allowed (the rest was discarded). */
	public record Result(int exitCode, String stdout, String stderr, boolean truncated) {
	}

	/**
	 * @param environment the complete environment of the child; nothing is inherited
	 * @param cancellation checked while the tool runs; when set, the process tree is killed and
	 * {@link AnalysisCancelledException} is thrown (the orchestrator decides whether that means a timeout)
	 * @param onStart called with the started process (tests use it to check the process is really gone)
	 */
	public static Result run(List<String> command, Path workingDirectory, Map<String, String> environment,
			CancellationToken cancellation, int maxStdoutBytes, int maxStderrBytes, Consumer<Process> onStart)
			throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		Process process = builder.start();
		if (onStart != null) {
			onStart.accept(process);
		}
		process.getOutputStream().close();
		Capture stdout = new Capture(process.getInputStream(), maxStdoutBytes);
		Capture stderr = new Capture(process.getErrorStream(), maxStderrBytes);
		Thread outThread = Thread.ofVirtual().name("tool-stdout").start(stdout);
		Thread errThread = Thread.ofVirtual().name("tool-stderr").start(stderr);
		try {
			while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
				if (cancellation.isCancelled()) {
					kill(process);
					throw new AnalysisCancelledException();
				}
			}
			outThread.join(5000);
			errThread.join(5000);
			return new Result(process.exitValue(), stdout.text(), stderr.text(), stdout.truncated || stderr.truncated);
		}
		catch (InterruptedException ex) {
			kill(process);
			throw ex;
		}
	}

	/** Kills the process and every descendant, then waits briefly for it to be gone. */
	static void kill(Process process) throws InterruptedException {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		process.waitFor(5, TimeUnit.SECONDS);
	}

	private static final class Capture implements Runnable {

		private final InputStream in;

		private final int max;

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private volatile boolean truncated;

		Capture(InputStream in, int max) {
			this.in = in;
			this.max = max;
		}

		@Override
		public void run() {
			byte[] chunk = new byte[8192];
			try {
				int read;
				while ((read = in.read(chunk)) >= 0) {
					int room = max - buffer.size();
					if (room > 0) {
						buffer.write(chunk, 0, Math.min(room, read));
					}
					if (read > room) {
						truncated = true; // keep reading so the tool never blocks on a full pipe
					}
				}
			}
			catch (IOException ex) {
				// the process was killed; whatever was captured so far is kept
			}
		}

		String text() {
			return buffer.toString(StandardCharsets.UTF_8);
		}

	}

}
