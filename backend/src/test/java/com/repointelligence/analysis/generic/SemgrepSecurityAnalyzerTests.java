package com.repointelligence.analysis.generic;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.orchestration.IsolatedRunner;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.AnalyzerSettings.AnalyzerConfig;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CancellationSource;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.tools.ProcessRunner;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.support.InMemoryWorkspace;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemgrepSecurityAnalyzerTests {

	private final SemgrepSecurityAnalyzer analyzer = new SemgrepSecurityAnalyzer();

	private static final String SEMGREP_JSON = """
			{
			  "version": "1.99.0",
			  "results": [
			    {"check_id": "repointel.java.weak-hash", "path": "src/Risky.java",
			     "start": {"line": 3, "col": 10}, "end": {"line": 3, "col": 45},
			     "extra": {"message": "MD5 and SHA-1 are broken for security purposes. Use SHA-256.", "severity": "WARNING",
			               "lines": "requires login", "metadata": {"category": "weak-crypto"}}},
			    {"check_id": "repointel.java.unsafe-deserialization", "path": "src/Risky.java",
			     "start": {"line": 5, "col": 3}, "end": {"line": 5, "col": 30},
			     "extra": {"message": "Java native deserialization is dangerous.", "severity": "ERROR"}}
			  ],
			  "errors": [{"message": "Syntax error at src/Broken.java:1"}],
			  "paths": {"scanned": ["src/Risky.java", "src/Other.java", "src/Third.java"],
			            "skipped": [{"path": "src/Huge.java", "reason": "file_size_limit"}]}
			}
			""";

	private static AnalyzerSettings withCommand(String... command) {
		return new AnalyzerSettings(Map.of(SemgrepSecurityAnalyzer.ID, new AnalyzerConfig(true, Map.of("command", command[0]))));
	}

	@Test
	void semgrepJsonParsesIntoFindingsAndACoverageSummary() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/Risky.java",
				"package x;\nclass Risky {\n  byte[] h = MessageDigest.getInstance(\"MD5\").digest(d);\n\n  Object o = new ObjectInputStream(in);\n}\n");

		AnalyzerResult result = analyzer.parse(ws.context(), Path.of("/workspace"), SEMGREP_JSON,
				SemgrepSecurityAnalyzer.RULE_RESOURCES, false);

		assertThat(result.findings()).hasSize(2);
		FindingResult hash = result.findings().get(0);
		assertThat(hash.ruleId()).isEqualTo("repointel.java.weak-hash");
		assertThat(hash.severity()).isEqualTo(Severity.MEDIUM);
		assertThat(hash.filePath()).isEqualTo("src/Risky.java");
		assertThat(hash.startLine()).isEqualTo(3);
		assertThat(hash.title()).isEqualTo("MD5 and SHA-1 are broken for security purposes.");
		assertThat(hash.evidence().get("excerpt").toString()).contains("MessageDigest.getInstance");
		assertThat(hash.evidence()).containsEntry("category", "weak-crypto");
		assertThat(result.findings().get(1).severity()).isEqualTo(Severity.HIGH);

		assertThat(result.configSummary()).containsEntry("filesScanned", 3)
			.containsEntry("filesSkipped", 1)
			.containsEntry("semgrepVersion", "1.99.0")
			.containsEntry("languages", List.of("java"));
		assertThat(result.configSummary().get("ruleFiles").toString()).contains("repointel-java.yml");
		assertThat(result.limitations()).anyMatch(l -> l.contains("3 file(s) scanned, 1 skipped"));
		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(result.warnings()).anyMatch(w -> w.contains("Syntax error"));
	}

	@Test
	void vendoredRulesShipWithTheApplicationAndNoRegistryPackIsReferenced() throws Exception {
		for (String resource : SemgrepSecurityAnalyzer.RULE_RESOURCES) {
			try (var in = SemgrepSecurityAnalyzer.class.getResourceAsStream("/" + resource)) {
				assertThat(in).as(resource).isNotNull();
				String rules = new String(in.readAllBytes());
				assertThat(rules).contains("repointel.java.sql-injection-concatenation").doesNotContain("p/java");
			}
		}
	}

	@Test
	void missingSemgrepIsUnavailableWithAReasonAndOtherAnalyzersAreUnaffected() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/A.java", "class A {}");
		var context = new com.repointelligence.analysis.spi.AnalysisContext(null, java.util.UUID.randomUUID(),
				java.util.UUID.randomUUID(), "a".repeat(40), ws, ws.fileInfos(), List.of(), withCommand("no-such-semgrep-binary-xyz"),
				null, null);

		Applicability applicability = analyzer.applicability(context);

		assertThat(applicability.kind()).isEqualTo(Applicability.Kind.UNAVAILABLE);
		assertThat(applicability.reason()).contains("Semgrep CLI not found").contains("no-such-semgrep-binary-xyz");
		// the secret scanner has no tool dependency, so it still applies
		assertThat(new GenericSecretScanAnalyzer().applicability(context).isApplicable()).isTrue();
	}

	@Test
	void notApplicableWithoutJavaFiles() {
		assertThat(analyzer.applicability(new InMemoryWorkspace().add("a.ts", "export {}").context()).kind())
			.isEqualTo(Applicability.Kind.NOT_APPLICABLE);
	}

	@Test
	void descriptorIsBasicAndNeedsTheSemgrepTool() {
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.BASIC);
		assertThat(analyzer.descriptor().requiredTools()).containsExactly("semgrep");
	}

	@Test
	void theChildEnvironmentCarriesNoApplicationSecrets(@TempDir Path home) {
		Map<String, String> env = SemgrepSecurityAnalyzer.minimalEnvironment(home);

		assertThat(env).containsEntry("SEMGREP_SEND_METRICS", "off");
		assertThat(env.keySet()).noneMatch(key -> key.contains("TOKEN") || key.contains("PASSWORD") || key.contains("SECRET"));
	}

	private static List<String> sleepCommand() {
		return System.getProperty("os.name").toLowerCase().contains("win") ? List.of("ping", "-n", "60", "127.0.0.1")
				: List.of("sleep", "60");
	}

	@Test
	void cancellingKillsTheProcessTreeAndStopsPromptly(@TempDir Path dir) throws Exception {
		CancellationSource cancel = new CancellationSource();
		AtomicReference<Process> started = new AtomicReference<>();
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(300);
			}
			catch (InterruptedException ex) {
				return;
			}
			cancel.cancel();
		});

		long begin = System.currentTimeMillis();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcessRunner.run(sleepCommand(), dir,
				SemgrepSecurityAnalyzer.minimalEnvironment(dir), cancel, 1024, 1024, started::set))
			.isInstanceOf(com.repointelligence.analysis.spi.AnalysisCancelledException.class);

		assertThat(System.currentTimeMillis() - begin).isLessThan(15_000);
		assertThat(started.get().isAlive()).as("the child process was killed").isFalse();
	}

	@Test
	void aProcessThatOutlivesTheDeadlineIsKilledAndRecordedAsTimedOut(@TempDir Path dir) throws Exception {
		ExecutorService executor = Executors.newCachedThreadPool();
		try {
			IsolatedRunner runner = new IsolatedRunner(executor, Clock.systemUTC());
			AtomicReference<Process> started = new AtomicReference<>();
			IsolatedRunner.Task<String> task = new IsolatedRunner.Task<>("slow-tool", token -> {
				ProcessRunner.run(sleepCommand(), dir, SemgrepSecurityAnalyzer.minimalEnvironment(dir), token, 1024, 1024,
						started::set);
				return "finished";
			});

			var outcomes = runner.runAll(List.of(task), Instant.now().plus(Duration.ofSeconds(2)), CancellationToken.NONE, outcome -> {
			});

			assertThat(outcomes).hasSize(1);
			assertThat(outcomes.get(0).status()).isEqualTo(ExecutionStatus.TIMED_OUT);
			long waitUntil = System.currentTimeMillis() + 10_000;
			while (started.get() != null && started.get().isAlive() && System.currentTimeMillis() < waitUntil) {
				Thread.sleep(100);
			}
			assertThat(started.get()).isNotNull();
			assertThat(started.get().isAlive()).as("the child process was killed").isFalse();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void processOutputIsCappedWithoutBlockingTheTool(@TempDir Path dir) throws Exception {
		List<String> noisy = System.getProperty("os.name").toLowerCase().contains("win")
				? List.of("cmd", "/c", "for /L %i in (1,1,3000) do @echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
				: List.of("sh", "-c", "yes aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | head -n 3000");

		ProcessRunner.Result result = ProcessRunner.run(noisy, dir, SemgrepSecurityAnalyzer.minimalEnvironment(dir),
				CancellationToken.NONE, 1000, 1000, null);

		assertThat(result.truncated()).isTrue();
		assertThat(result.stdout().length()).isLessThanOrEqualTo(1000);
	}

}
