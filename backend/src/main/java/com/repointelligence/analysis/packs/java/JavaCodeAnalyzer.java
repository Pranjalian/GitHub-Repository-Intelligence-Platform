package com.repointelligence.analysis.packs.java;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CodeAnalyzer;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.FileType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Java code-quality findings from PMD 7, run in-process. PMD only parses source: nothing from the
 * repository is compiled or executed. There is no classpath (dependencies are never downloaded), so rules
 * that benefit from type resolution may be less precise; that is declared as a limitation.
 *
 * <p>
 * Files are analysed in batches so cancellation and the run deadline are honoured between batches. A file
 * PMD cannot parse becomes a warning, not a failure.
 */
@Component
public class JavaCodeAnalyzer implements CodeAnalyzer {

	public static final String ID = "java-code-analyzer";

	public static final String RULESET = "rulesets/repointel-java-quality.xml";

	private static final Logger log = LoggerFactory.getLogger(JavaCodeAnalyzer.class);

	private static final String VERSION = "1.0.0";

	private static final String JAVA_LANGUAGE_VERSION = "21";

	private static final int BATCH_SIZE = 150;

	private static final int MAX_EXCERPT = 300;

	/** PMD priority (1 highest) to finding severity. */
	static final Map<Integer, Severity> PRIORITY_TO_SEVERITY = Map.of(1, Severity.CRITICAL, 2, Severity.HIGH, 3,
			Severity.MEDIUM, 4, Severity.LOW, 5, Severity.INFO);

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Java Code Quality (PMD)", VERSION, AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC)
		.languages("Java")
		.description("PMD 7 with a curated, versioned ruleset (design, bestpractices, errorprone, codestyle subsets). "
				+ "Parses source only; nothing is compiled or executed.")
		.limitations("No auxclasspath: dependencies are not downloaded, so rules that rely on full type resolution "
				+ "may be less precise.",
				"Duplication detection (PMD CPD) is not enabled; the duplication capability is not configured.",
				"Test sources are not analyzed unless enabled in the analyzer options.")
		.build();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		return javaFiles(context).isEmpty() ? Applicability.notApplicable() : Applicability.applicable();
	}

	/** Java source files to analyze: main sources, plus tests when {@code include-tests} is true. */
	private static List<FileInfo> javaFiles(AnalysisContext context) {
		boolean includeTests = Boolean.parseBoolean(context.settings().option(ID, "include-tests", "false"));
		return context.files()
			.stream()
			.filter(FileInfo::extracted)
			.filter(f -> f.path().endsWith(".java"))
			.filter(f -> f.fileType() == FileType.SOURCE || (includeTests && f.fileType() == FileType.TEST))
			.sorted(java.util.Comparator.comparing(FileInfo::path))
			.toList();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) throws Exception {
		List<FileInfo> files = javaFiles(context);
		Path root = context.workspace().resolveForTool();
		Set<String> moduleDirectories = moduleDirectories(context.files());

		AnalyzerResult.Builder result = AnalyzerResult.builder();
		int analysed = 0;
		int violations = 0;
		for (int from = 0; from < files.size(); from += BATCH_SIZE) {
			context.cancellation().throwIfCancelled();
			if (context.remaining().isNegative()) {
				result.warning("The time budget ended after " + analysed + " of " + files.size()
						+ " Java files; the rest were not analyzed.");
				break;
			}
			List<FileInfo> batch = files.subList(from, Math.min(files.size(), from + BATCH_SIZE));
			Report report = runPmd(root, batch);
			for (Report.ConfigurationError error : report.getConfigurationErrors()) {
				result.warning("PMD ruleset problem: " + error.issue());
			}
			for (Report.ProcessingError error : report.getProcessingErrors()) {
				result.warning(relative(root, error.getFileId().getOriginalPath()) + ": PMD could not analyze this file ("
						+ firstLine(error.getMsg()) + ").");
			}
			for (RuleViolation violation : report.getViolations()) {
				result.finding(toFinding(context, root, violation, moduleDirectories));
				violations++;
			}
			analysed += batch.size();
		}
		result.configSummary(Map.of("filesAnalyzed", analysed, "violations", violations, "ruleset", RULESET,
				"languageVersion", JAVA_LANGUAGE_VERSION));
		DESCRIPTOR.limitations().forEach(result::limitation);
		return result.build();
	}

	private static Report runPmd(Path root, List<FileInfo> batch) {
		PMDConfiguration config = new PMDConfiguration();
		config.setDefaultLanguageVersion(
				LanguageRegistry.PMD.getLanguageById("java").getVersion(JAVA_LANGUAGE_VERSION));
		config.addRuleSet(RULESET);
		config.setIgnoreIncrementalAnalysis(true);
		config.setThreads(2);
		try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
			for (FileInfo file : batch) {
				pmd.files().addFile(root.resolve(file.path()));
			}
			return pmd.performAnalysisAndCollectReport();
		}
	}

	private static FindingResult toFinding(AnalysisContext context, Path root, RuleViolation violation,
			Set<String> moduleDirectories) {
		Rule rule = violation.getRule();
		String path = relative(root, violation.getFileId().getOriginalPath());
		Severity severity = PRIORITY_TO_SEVERITY.getOrDefault(rule.getPriority().getPriority(), Severity.MEDIUM);
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("rule", rule.getName());
		evidence.put("ruleset", rule.getRuleSetName());
		evidence.put("priority", rule.getPriority().getPriority());
		String excerpt = excerpt(context, path, violation.getBeginLine());
		if (excerpt != null) {
			evidence.put("excerpt", excerpt);
		}
		if (rule.getExternalInfoUrl() != null) {
			evidence.put("infoUrl", rule.getExternalInfoUrl());
		}
		String description = rule.getDescription() == null ? violation.getDescription()
				: rule.getDescription().strip().replaceAll("\\s+", " ");
		return new FindingResult(AnalysisCategory.CODE_QUALITY, rule.getName(), severity, Confidence.LIKELY,
				violation.getDescription(), description, path, moduleOf(path, moduleDirectories),
				violation.getBeginLine(), violation.getEndLine(), evidence, AnalysisMethod.DETERMINISTIC_SCANNER,
				rule.getExternalInfoUrl() == null ? null : "See the rule documentation: " + rule.getExternalInfoUrl());
	}

	/** The line the violation starts on, trimmed and bounded; the orchestrator redacts it before storing. */
	private static String excerpt(AnalysisContext context, String path, int line) {
		try {
			String source = context.workspace().readString(path, 512 * 1024L);
			String[] lines = source.split("\r?\n", -1);
			if (line >= 1 && line <= lines.length) {
				String text = lines[line - 1].strip();
				return text.length() > MAX_EXCERPT ? text.substring(0, MAX_EXCERPT) : text;
			}
		}
		catch (IOException | RuntimeException ex) {
			log.debug("No excerpt for {}:{}", path, line);
		}
		return null;
	}

	/** Directories that hold a pom.xml, longest first, to place a file in its module. */
	private static Set<String> moduleDirectories(List<FileInfo> files) {
		Set<String> directories = new TreeSet<>(java.util.Comparator.comparing(String::length).reversed()
			.thenComparing(java.util.Comparator.naturalOrder()));
		for (FileInfo file : files) {
			if (file.path().equals("pom.xml")) {
				directories.add("");
			}
			else if (file.path().endsWith("/pom.xml")) {
				directories.add(file.path().substring(0, file.path().length() - "/pom.xml".length()));
			}
		}
		return directories;
	}

	static String moduleOf(String path, Set<String> moduleDirectories) {
		for (String directory : moduleDirectories) {
			if (directory.isEmpty() || path.startsWith(directory + "/")) {
				return directory.isEmpty() ? "." : directory;
			}
		}
		return null;
	}

	private static String relative(Path root, String original) {
		Path path = Path.of(original);
		try {
			if (path.isAbsolute()) {
				return root.relativize(path.normalize()).toString().replace('\\', '/');
			}
		}
		catch (IllegalArgumentException ex) {
			// different roots: fall through
		}
		return original.replace('\\', '/');
	}

	private static String firstLine(String message) {
		if (message == null) {
			return "unknown error";
		}
		int newline = message.indexOf('\n');
		return newline > 0 ? message.substring(0, newline) : message;
	}

}
