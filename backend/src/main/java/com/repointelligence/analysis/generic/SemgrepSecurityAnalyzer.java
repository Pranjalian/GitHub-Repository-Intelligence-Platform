package com.repointelligence.analysis.generic;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.SecurityAnalyzer;
import com.repointelligence.analysis.tools.ProcessRunner;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Semgrep static analysis with vendored local rules, run as an external process. Available only when the
 * Semgrep CLI is found ({@code UNAVAILABLE} with a reason otherwise); the command is configurable with
 * {@code analysis.analyzers.semgrep-security-analyzer.options.command}. It never pulls registry rule packs,
 * disables metrics and version checks, passes a minimal environment, caps output, and kills the process
 * tree on cancellation or deadline. It reports what it actually covered: rule files, languages, and files
 * scanned versus skipped.
 */
@Component
public class SemgrepSecurityAnalyzer implements SecurityAnalyzer {

	public static final String ID = "semgrep-security-analyzer";

	private static final Logger log = LoggerFactory.getLogger(SemgrepSecurityAnalyzer.class);

	/** Vendored rule files by language folder; the Java pack contributes its folder here. */
	static final List<String> RULE_RESOURCES = List.of("semgrep-rules/java/repointel-java.yml");

	private static final int MAX_STDOUT_BYTES = 20 * 1024 * 1024;

	private static final int MAX_STDERR_BYTES = 64 * 1024;

	private static final long PROBE_TTL_MILLIS = 60_000;

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Semgrep Security Rules", "1.0.0", AnalysisCategory.SECURITY, CapabilityLevel.BASIC)
		.languages("Java")
		.requiredTools("semgrep")
		.description("Semgrep with vendored local rules (SQL injection, unsafe deserialization, weak crypto, "
				+ "sensitive logging, insecure configuration). Rules ship with the application; no registry packs are pulled.")
		.limitations("Only the languages with vendored rule files are covered (currently Java).",
				"Findings are static-analysis matches and may include false positives.")
		.build();

	private record Probe(boolean available, String detail, long checkedAt) {
	}

	private final JsonMapper mapper = JsonMapper.builder().build();

	private final Map<String, Probe> probes = new ConcurrentHashMap<>();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		boolean hasJava = context.files().stream().anyMatch(f -> f.extracted() && f.path().endsWith(".java"));
		if (!hasJava) {
			return Applicability.notApplicable();
		}
		String command = command(context);
		Probe probe = probe(command);
		return probe.available() ? Applicability.applicable()
				: Applicability.unavailable("Semgrep CLI not found (command '" + command + "'): " + probe.detail());
	}

	private static String command(AnalysisContext context) {
		return context.settings().option(ID, "command", "semgrep");
	}

	private Probe probe(String command) {
		Probe cached = probes.get(command);
		if (cached != null && System.currentTimeMillis() - cached.checkedAt() < PROBE_TTL_MILLIS) {
			return cached;
		}
		Probe fresh;
		try {
			ProcessRunner.Result result = ProcessRunner.run(List.of(command, "--version"), Path.of("."), minimalEnvironment(Path.of(".")),
					CancellationToken.NONE, 4096, 4096, null);
			fresh = result.exitCode() == 0 ? new Probe(true, result.stdout().strip(), System.currentTimeMillis())
					: new Probe(false, "'--version' exited with " + result.exitCode(), System.currentTimeMillis());
		}
		catch (IOException ex) {
			fresh = new Probe(false, "it could not be started", System.currentTimeMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			fresh = new Probe(false, "the version check was interrupted", System.currentTimeMillis());
		}
		probes.put(command, fresh);
		return fresh;
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) throws Exception {
		Path workspace = context.workspace().resolveForTool().toAbsolutePath().normalize();
		Path scratch = Files.createTempDirectory("repointel-semgrep");
		try {
			Path rules = extractRules(scratch);
			List<String> command = new ArrayList<>(List.of(command(context), "scan", "--config", rules.toString(), "--json",
					"--metrics=off", "--disable-version-check", "--timeout",
					context.settings().option(ID, "timeout-per-file", "10"), "--max-target-bytes",
					context.settings().option(ID, "max-target-bytes", "1000000"), "--jobs",
					context.settings().option(ID, "jobs", "2"), workspace.toString()));
			ProcessRunner.Result result = ProcessRunner.run(command, workspace, minimalEnvironment(scratch),
					context.cancellation(), MAX_STDOUT_BYTES, MAX_STDERR_BYTES, null);
			if (result.exitCode() != 0 && result.exitCode() != 1) {
				throw new IllegalStateException("Semgrep exited with code " + result.exitCode() + ": " + firstLine(result.stderr()));
			}
			if (result.truncated()) {
				log.warn("Semgrep output exceeded its size cap and was truncated");
			}
			return parse(context, workspace, result.stdout(), RULE_RESOURCES, result.truncated());
		}
		finally {
			deleteTree(scratch);
		}
	}

	/** Turns Semgrep's JSON into findings and a coverage summary. Package-private for tests. */
	AnalyzerResult parse(AnalysisContext context, Path workspace, String json, List<String> ruleFiles, boolean truncated) {
		JsonNode root;
		try {
			root = mapper.readTree(json);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Semgrep produced output that is not valid JSON");
		}
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		JsonNode results = root.path("results");
		int count = 0;
		for (JsonNode match : results) {
			String path = relative(workspace, match.path("path").asString(""));
			int startLine = match.path("start").path("line").asInt(0);
			int endLine = match.path("end").path("line").asInt(startLine);
			JsonNode extra = match.path("extra");
			String message = extra.path("message").asString("Semgrep rule matched");
			String ruleId = match.path("check_id").asString("semgrep");
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("rule", ruleId);
			String excerpt = excerpt(context, path, startLine);
			if (excerpt != null) {
				evidence.put("excerpt", excerpt);
			}
			JsonNode category = extra.path("metadata").path("category");
			if (!category.isMissingNode()) {
				evidence.put("category", category.asString());
			}
			result.finding(new FindingResult(AnalysisCategory.SECURITY, ruleId, severity(extra.path("severity").asString("")),
					Confidence.LIKELY, firstSentence(message), message, path, null, startLine > 0 ? startLine : null,
					endLine > 0 ? endLine : null, evidence, AnalysisMethod.DETERMINISTIC_SCANNER,
					"Review the code at this location and apply the fix described in the rule message."));
			count++;
		}
		for (JsonNode error : root.path("errors")) {
			result.warning("Semgrep reported: " + firstLine(error.path("message").asString(error.toString())));
		}
		if (truncated) {
			result.warning("Semgrep's output exceeded its size limit and was truncated; some findings may be missing.");
		}
		JsonNode scanned = root.path("paths").path("scanned");
		JsonNode skipped = root.path("paths").path("skipped");
		Map<String, Object> coverage = new LinkedHashMap<>();
		coverage.put("semgrepVersion", root.path("version").asString("unknown"));
		coverage.put("languages", List.of("java"));
		coverage.put("ruleFiles", ruleFiles);
		coverage.put("filesScanned", scanned.size());
		coverage.put("filesSkipped", skipped.size());
		coverage.put("findings", count);
		result.configSummary(coverage);
		DESCRIPTOR.limitations().forEach(result::limitation);
		result.limitation("Coverage: " + scanned.size() + " file(s) scanned, " + skipped.size()
				+ " skipped, with rule files " + ruleFiles + ".");
		return result.build();
	}

	private static Severity severity(String semgrep) {
		return switch (semgrep.toUpperCase(java.util.Locale.ROOT)) {
			case "ERROR", "CRITICAL", "HIGH" -> Severity.HIGH;
			case "WARNING", "MEDIUM" -> Severity.MEDIUM;
			default -> Severity.LOW;
		};
	}

	private static String firstSentence(String message) {
		int dot = message.indexOf(". ");
		String sentence = dot > 0 ? message.substring(0, dot + 1) : message;
		return sentence.length() > 200 ? sentence.substring(0, 200) : sentence;
	}

	private static String firstLine(String text) {
		if (text == null || text.isBlank()) {
			return "no error output";
		}
		String line = text.strip().lines().findFirst().orElse("");
		return line.length() > 200 ? line.substring(0, 200) : line;
	}

	private static String relative(Path workspace, String reported) {
		Path path = Path.of(reported);
		if (path.isAbsolute()) {
			try {
				return workspace.relativize(path.normalize()).toString().replace('\\', '/');
			}
			catch (IllegalArgumentException ex) {
				return reported.replace('\\', '/');
			}
		}
		String normalized = reported.replace('\\', '/');
		return normalized.startsWith("./") ? normalized.substring(2) : normalized;
	}

	private static String excerpt(AnalysisContext context, String path, int line) {
		try {
			String[] lines = context.workspace().readString(path, 512 * 1024L).split("\r?\n", -1);
			if (line >= 1 && line <= lines.length) {
				String text = lines[line - 1].strip();
				return text.length() > 300 ? text.substring(0, 300) : text;
			}
		}
		catch (IOException | RuntimeException ex) {
			log.debug("No excerpt for {}:{}", path, line);
		}
		return null;
	}

	private static Path extractRules(Path scratch) throws IOException {
		Path rules = Files.createDirectories(scratch.resolve("rules"));
		for (String resource : RULE_RESOURCES) {
			try (InputStream in = SemgrepSecurityAnalyzer.class.getResourceAsStream("/" + resource)) {
				if (in == null) {
					throw new IllegalStateException("Missing vendored Semgrep rules: " + resource);
				}
				Path target = rules.resolve(resource.substring("semgrep-rules/".length()));
				Files.createDirectories(target.getParent());
				Files.copy(in, target);
			}
		}
		return rules;
	}

	/** Only what Semgrep needs to run; none of the application's environment (tokens, passwords) is passed on. */
	static Map<String, String> minimalEnvironment(Path home) {
		Map<String, String> env = new LinkedHashMap<>();
		for (String key : List.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP", "PATHEXT", "LANG")) {
			String value = System.getenv(key);
			if (value != null) {
				env.put(key, value);
			}
		}
		env.put("HOME", home.toAbsolutePath().toString());
		env.put("USERPROFILE", home.toAbsolutePath().toString());
		env.put("SEMGREP_SEND_METRICS", "off");
		env.put("SEMGREP_ENABLE_VERSION_CHECK", "0");
		env.put("PYTHONIOENCODING", "utf-8");
		return env;
	}

	private static void deleteTree(Path path) {
		try (Stream<Path> walk = Files.walk(path)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		catch (IOException ex) {
			log.debug("Could not remove {}", path);
		}
	}

}
