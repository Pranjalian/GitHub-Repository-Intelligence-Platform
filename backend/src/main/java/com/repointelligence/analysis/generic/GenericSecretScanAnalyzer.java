package com.repointelligence.analysis.generic;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.analysis.spi.SecurityAnalyzer;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

/**
 * Finds hard-coded secrets in every repository, whatever its technology. Rules are data
 * ({@code rules/secrets.yml}). The raw secret value is never stored, logged or returned: it is replaced
 * by {@code [REDACTED]} in the evidence excerpt before the finding leaves this class, and is not
 * mentioned in the title or description.
 */
@Component
public class GenericSecretScanAnalyzer implements SecurityAnalyzer {

	public static final String ID = "generic-secret-scan-analyzer";

	public static final String REDACTED = "[REDACTED]";

	private static final Logger log = LoggerFactory.getLogger(GenericSecretScanAnalyzer.class);

	private static final String RULES_RESOURCE = "/rules/secrets.yml";

	private static final long MAX_FILE_BYTES = 1024 * 1024L;

	private static final int MAX_LINE_LENGTH = 2000;

	private static final int MAX_EXCERPT = 240;

	private static final int MAX_FINDINGS_PER_FILE = 25;

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Generic Secret Scanner", "1.0.0", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC)
		.description("Pattern- and entropy-based detection of hard-coded secrets (private keys, cloud keys, tokens, "
				+ "credentials in code and configuration). Runs for every repository.")
		.limitations("Pattern matching can miss secrets that match no rule and can flag non-secrets; findings are "
				+ "'likely' or 'possible', not verified.",
				"Secrets are never verified against the service they belong to.",
				"Binary files, files over 1 MB and lock files are not scanned.")
		.build();

	private static final java.util.Set<String> SKIPPED_NAMES = java.util.Set.of("package-lock.json", "pnpm-lock.yaml",
			"yarn.lock", "Cargo.lock", "Gemfile.lock", "go.sum");

	/** One compiled rule from the YAML file. */
	record Rule(String id, String title, Pattern regex, List<String> keywords, Double entropy, List<String> globs,
			Severity severity, Confidence confidence) {
	}

	private final List<Rule> rules;

	private final List<Pattern> placeholderValues;

	private final List<String> placeholderLines;

	private final AntPathMatcher pathMatcher = new AntPathMatcher("/");

	public GenericSecretScanAnalyzer() {
		this(RULES_RESOURCE);
	}

	@SuppressWarnings("unchecked")
	GenericSecretScanAnalyzer(String resource) {
		List<Rule> loaded = new ArrayList<>();
		List<Pattern> values = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		try (InputStream in = GenericSecretScanAnalyzer.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Secret rules not found: " + resource);
			}
			Map<String, Object> root = new Yaml().load(in);
			Map<String, Object> allowlist = (Map<String, Object>) root.getOrDefault("allowlist", Map.of());
			for (String pattern : (List<String>) allowlist.getOrDefault("value-patterns", List.of())) {
				values.add(Pattern.compile(pattern));
			}
			lines.addAll((List<String>) allowlist.getOrDefault("line-markers", List.of()));
			for (Map<String, Object> raw : (List<Map<String, Object>>) root.get("rules")) {
				loaded.add(new Rule((String) raw.get("id"), (String) raw.get("title"),
						Pattern.compile((String) raw.get("regex")),
						lower((List<String>) raw.getOrDefault("keywords", List.of())),
						raw.get("entropy") == null ? null : ((Number) raw.get("entropy")).doubleValue(),
						(List<String>) raw.getOrDefault("globs", List.of()),
						Severity.valueOf((String) raw.get("severity")), Confidence.valueOf((String) raw.get("confidence"))));
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read secret rules", ex);
		}
		this.rules = List.copyOf(loaded);
		this.placeholderValues = List.copyOf(values);
		this.placeholderLines = List.copyOf(lines);
		log.info("GenericSecretScanAnalyzer loaded {} rules", rules.size());
	}

	private static List<String> lower(List<String> values) {
		return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList();
	}

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		return Applicability.applicable();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		int scanned = 0;
		int findings = 0;
		for (FileInfo file : context.files()) {
			if (!shouldScan(file)) {
				continue;
			}
			context.cancellation().throwIfCancelled();
			String content;
			try {
				content = context.workspace().readString(file.path(), MAX_FILE_BYTES);
			}
			catch (IOException | RuntimeException ex) {
				result.warning(file.path() + ": could not be read, so it was not scanned.");
				continue;
			}
			scanned++;
			List<FindingResult> found = scan(file.path(), content, modulePath(file.path(), context));
			found.forEach(result::finding);
			findings += found.size();
		}
		result.configSummary(Map.of("filesScanned", scanned, "rules", rules.size(), "findings", findings));
		DESCRIPTOR.limitations().forEach(result::limitation);
		return result.build();
	}

	private static boolean shouldScan(FileInfo file) {
		if (!file.extracted() || file.sizeBytes() > MAX_FILE_BYTES) {
			return false;
		}
		String name = file.path().substring(file.path().lastIndexOf('/') + 1);
		return !SKIPPED_NAMES.contains(name) && !name.endsWith(".min.js") && !name.endsWith(".map");
	}

	/** The nearest directory with a build manifest, so findings can be filtered by module. */
	private static String modulePath(String path, AnalysisContext context) {
		String best = null;
		for (FileInfo file : context.files()) {
			String name = file.path().substring(file.path().lastIndexOf('/') + 1);
			if (!name.equals("pom.xml") && !name.equals("package.json")) {
				continue;
			}
			String dir = file.path().contains("/") ? file.path().substring(0, file.path().lastIndexOf('/')) : "";
			if ((dir.isEmpty() || path.startsWith(dir + "/")) && (best == null || dir.length() > best.length())) {
				best = dir;
			}
		}
		return best == null ? null : best.isEmpty() ? "." : best;
	}

	/** Scans one file's text. Package-private for tests. */
	List<FindingResult> scan(String path, String content, String modulePath) {
		List<FindingResult> findings = new ArrayList<>();
		String[] lines = content.split("\r?\n", -1);
		Map<String, Boolean> globCache = new HashMap<>();
		for (int i = 0; i < lines.length && findings.size() < MAX_FINDINGS_PER_FILE; i++) {
			String line = lines[i];
			if (line.length() > MAX_LINE_LENGTH || line.isBlank() || isPlaceholderLine(line)) {
				continue;
			}
			String lowerLine = line.toLowerCase(Locale.ROOT);
			// first collect every secret on the line, so each finding's excerpt hides all of them
			List<Rule> matchedRules = new ArrayList<>();
			List<String> secrets = new ArrayList<>();
			for (Rule rule : rules) {
				if (!rule.keywords().isEmpty() && rule.keywords().stream().noneMatch(lowerLine::contains)) {
					continue;
				}
				if (!rule.globs().isEmpty() && !globCache.computeIfAbsent(rule.id(),
						id -> rule.globs().stream().anyMatch(glob -> pathMatcher.match(glob, path)))) {
					continue;
				}
				Matcher matcher = rule.regex().matcher(line);
				while (matcher.find()) {
					String secret = secretOf(matcher);
					if (secret.isEmpty() || isPlaceholder(secret) || !hasEnoughEntropy(rule, secret)) {
						continue;
					}
					matchedRules.add(rule);
					secrets.add(secret);
					break; // one finding per rule per line
				}
			}
			if (matchedRules.isEmpty()) {
				continue;
			}
			String redactedLine = redact(line, secrets);
			for (Rule rule : matchedRules) {
				findings.add(finding(rule, path, modulePath, i + 1, redactedLine));
			}
		}
		return findings;
	}

	private static String secretOf(Matcher matcher) {
		try {
			String named = matcher.group("secret");
			if (named != null) {
				return named;
			}
		}
		catch (IllegalArgumentException ex) {
			// no group named "secret"
		}
		if (matcher.groupCount() >= 1 && matcher.group(1) != null) {
			return matcher.group(1);
		}
		return matcher.group();
	}

	private boolean isPlaceholder(String value) {
		return placeholderValues.stream().anyMatch(pattern -> pattern.matcher(value).matches());
	}

	private boolean isPlaceholderLine(String line) {
		return placeholderLines.stream().anyMatch(line::contains);
	}

	private static boolean hasEnoughEntropy(Rule rule, String secret) {
		return rule.entropy() == null || entropy(secret) >= rule.entropy();
	}

	/** Shannon entropy in bits per character. */
	static double entropy(String value) {
		Map<Character, Integer> counts = new HashMap<>();
		for (char c : value.toCharArray()) {
			counts.merge(c, 1, Integer::sum);
		}
		double entropy = 0;
		for (int count : counts.values()) {
			double p = (double) count / value.length();
			entropy -= p * (Math.log(p) / Math.log(2));
		}
		return entropy;
	}

	/** Replaces every secret value, longest first so a value that contains another is hidden entirely. */
	private static String redact(String line, List<String> secrets) {
		String redacted = line;
		for (String secret : secrets.stream().sorted(java.util.Comparator.comparing(String::length).reversed()).toList()) {
			redacted = redacted.replace(secret, REDACTED);
		}
		return redacted;
	}

	private FindingResult finding(Rule rule, String path, String modulePath, int line, String text) {
		String redacted = text.strip();
		if (redacted.length() > MAX_EXCERPT) {
			int at = Math.max(0, redacted.indexOf(REDACTED) - 80);
			redacted = redacted.substring(at, Math.min(redacted.length(), at + MAX_EXCERPT));
		}
		Map<String, Object> evidence = new java.util.LinkedHashMap<>();
		evidence.put("rule", rule.id());
		evidence.put("excerpt", redacted);
		evidence.put("note", "The value is redacted and was never stored.");
		return new FindingResult(AnalysisCategory.SECURITY, rule.id(), rule.severity(), rule.confidence(),
				(rule.confidence() == Confidence.LIKELY ? "Likely hard-coded secret: " : "Possible hard-coded secret: ")
						+ rule.title(),
				"A value that looks like " + rule.title().toLowerCase(Locale.ROOT) + " is committed in " + path + ". "
						+ "The value is not shown.",
				path, modulePath, line, line, evidence, AnalysisMethod.DETERMINISTIC_SCANNER,
				"Rotate the credential, remove it from the repository and its history, and load it from the "
						+ "environment or a secret manager.");
	}

}
