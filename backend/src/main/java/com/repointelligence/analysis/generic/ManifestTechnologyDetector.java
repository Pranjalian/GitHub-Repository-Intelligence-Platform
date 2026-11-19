package com.repointelligence.analysis.generic;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Data-driven technology detector driven by {@code technology-signatures.yml} on the classpath.
 * Each rule describes a set of Ant-style path globs; when any file in the snapshot matches, the
 * technology is emitted with its configured confidence and optional version extracted by regex.
 *
 * <p>Adding a new technology requires only a new entry in the YAML file — no Java change.
 */
@Component
public class ManifestTechnologyDetector implements TechnologyDetector {

	static final String ID = "manifest-technology-detector";

	private static final String VERSION = "1.0.0";

	private static final String SIGNATURES_RESOURCE = "/technology-signatures.yml";

	private static final Logger log = LoggerFactory.getLogger(ManifestTechnologyDetector.class);

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Manifest Technology Detector", VERSION, AnalysisCategory.TECHNOLOGY, CapabilityLevel.GENERIC)
		.description("Data-driven detector: reads technology-signatures.yml to identify build tools, "
				+ "package managers, languages, frameworks and deployment tooling from manifest files.")
		.build();

	private final List<SignatureRule> rules;

	public ManifestTechnologyDetector() {
		this(SIGNATURES_RESOURCE);
	}

	/** Package-private constructor for tests that supply a custom resource path. */
	ManifestTechnologyDetector(String resourcePath) {
		this.rules = loadRules(resourcePath);
		log.info("ManifestTechnologyDetector loaded {} signature rules from {}", rules.size(), resourcePath);
	}

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public List<DetectedTechnology> detect(AnalysisContext context) {
		List<FileInfo> files = context.files();

		// rule id → (technology + all matched evidence paths)
		Map<String, RuleMatch> matches = new LinkedHashMap<>();

		for (FileInfo file : files) {
			String filePath = file.path();
			for (SignatureRule rule : rules) {
				if (rule.matches(filePath)) {
					RuleMatch match = matches.computeIfAbsent(rule.id(), k -> new RuleMatch(rule));
					match.addEvidence(filePath);
				}
			}
		}

		// For rules with version-from-content: attempt to extract version from the first matched file.
		List<DetectedTechnology> results = new ArrayList<>();
		for (RuleMatch match : matches.values()) {
			SignatureRule rule = match.rule();
			String version = null;
			if (rule.versionPattern() != null && !match.evidencePaths().isEmpty()) {
				String firstPath = match.evidencePaths().get(0);
				version = extractVersion(context, firstPath, rule.versionPattern());
			}
			List<Evidence> evidenceList = match.evidencePaths()
				.stream()
				.map(p -> new Evidence(p, null, "matched rule " + rule.id()))
				.toList();
			results.add(new DetectedTechnology(rule.technology(), rule.kind(), version, rule.confidence(), evidenceList,
					ID, null));
		}

		return List.copyOf(results);
	}

	private String extractVersion(AnalysisContext context, String filePath, Pattern pattern) {
		try {
			String content = context.workspace().readString(filePath, 32 * 1024L);
			if (content == null) {
				return null;
			}
			Matcher m = pattern.matcher(content);
			return m.find() ? m.group(1) : null;
		}
		catch (Exception ex) {
			log.debug("Version extraction failed for {}: {}", filePath, ex.getMessage());
			return null;
		}
	}

	// ── Rule loading ──────────────────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static List<SignatureRule> loadRules(String resourcePath) {
		try (InputStream in = ManifestTechnologyDetector.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				log.warn("technology-signatures.yml not found at {}; no manifest detection will occur", resourcePath);
				return List.of();
			}
			Yaml yaml = new Yaml();
			Map<String, Object> root = yaml.load(in);
			List<Map<String, Object>> rawRules = (List<Map<String, Object>>) root.get("rules");
			if (rawRules == null) {
				return List.of();
			}
			List<SignatureRule> loaded = new ArrayList<>(rawRules.size());
			for (Map<String, Object> raw : rawRules) {
				try {
					loaded.add(SignatureRule.from(raw));
				}
				catch (Exception ex) {
					log.warn("Skipping invalid signature rule {}: {}", raw.get("id"), ex.getMessage());
				}
			}
			return List.copyOf(loaded);
		}
		catch (IOException ex) {
			log.error("Failed to load technology-signatures.yml", ex);
			return List.of();
		}
	}

	// ── Internal types ────────────────────────────────────────────────────────────────────────────

	/** Immutable compiled form of one rule from the YAML file. */
	static final class SignatureRule {

		private final String id;

		private final String technology;

		private final TechnologyKind kind;

		private final Confidence confidence;

		private final List<Pattern> matchers;

		private final Pattern versionPattern;

		private SignatureRule(String id, String technology, TechnologyKind kind, Confidence confidence,
				List<Pattern> matchers, Pattern versionPattern) {
			this.id = id;
			this.technology = technology;
			this.kind = kind;
			this.confidence = confidence;
			this.matchers = matchers;
			this.versionPattern = versionPattern;
		}

		@SuppressWarnings("unchecked")
		static SignatureRule from(Map<String, Object> raw) {
			String id = (String) raw.get("id");
			String tech = (String) raw.get("technology");
			TechnologyKind kind = TechnologyKind.valueOf((String) raw.get("kind"));
			Confidence confidence = Confidence.valueOf((String) raw.get("confidence"));
			List<String> globs = (List<String>) raw.get("globs");
			String versionRegex = (String) raw.get("version-from-content");

			List<Pattern> matchers = globs.stream().map(SignatureRule::globToRegex).toList();
			Pattern versionPattern = versionRegex != null ? Pattern.compile(versionRegex, Pattern.MULTILINE) : null;
			return new SignatureRule(id, tech, kind, confidence, matchers, versionPattern);
		}

		/**
		 * Converts an Ant-style glob to a regex over forward-slash paths. A leading-segment
		 * double-star followed by a slash matches zero or more directories, so a pattern such as
		 * "**&#47;pom.xml" also matches a root-level pom.xml.
		 */
		static Pattern globToRegex(String glob) {
			StringBuilder sb = new StringBuilder("^");
			for (int i = 0; i < glob.length(); i++) {
				char c = glob.charAt(i);
				if (c == '*') {
					if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
						i++;
						if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
							i++;
							sb.append("(?:.*/)?");
						}
						else {
							sb.append(".*");
						}
					}
					else {
						sb.append("[^/]*");
					}
				}
				else if (c == '?') {
					sb.append("[^/]");
				}
				else {
					sb.append(Pattern.quote(String.valueOf(c)));
				}
			}
			return Pattern.compile(sb.append('$').toString());
		}

		boolean matches(String filePath) {
			String normalized = filePath.replace('\\', '/');
			for (Pattern matcher : matchers) {
				if (matcher.matcher(normalized).matches()) {
					return true;
				}
			}
			return false;
		}

		String id() {
			return id;
		}

		String technology() {
			return technology;
		}

		TechnologyKind kind() {
			return kind;
		}

		Confidence confidence() {
			return confidence;
		}

		Pattern versionPattern() {
			return versionPattern;
		}

	}

	/** Accumulates evidence paths for one rule during a single detection pass. */
	private static final class RuleMatch {

		private final SignatureRule rule;

		private final List<String> evidencePaths = new ArrayList<>();

		RuleMatch(SignatureRule rule) {
			this.rule = rule;
		}

		void addEvidence(String path) {
			evidencePaths.add(path);
		}

		SignatureRule rule() {
			return rule;
		}

		List<String> evidencePaths() {
			return evidencePaths;
		}

	}

}
