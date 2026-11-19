package com.repointelligence.analysis.registry;

import com.repointelligence.analysis.application.AnalyzerSetHashProvider;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CodeAnalyzer;
import com.repointelligence.analysis.spi.DependencyAnalyzer;
import com.repointelligence.analysis.spi.RepositoryAnalyzer;
import com.repointelligence.analysis.spi.SecurityAnalyzer;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.analysis.spi.TestAnalyzer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Discovers every {@link TechnologyDetector} and {@link Analyzer} bean, validates them at startup and
 * computes the analyzer-set hash that decides whether a completed run can be reused. New analyzer
 * packs register simply by being Spring beans: nothing here knows about specific languages.
 */
@Component
public class AnalyzerRegistry implements AnalyzerSetHashProvider {

	private static final Logger log = LoggerFactory.getLogger(AnalyzerRegistry.class);

	private final List<TechnologyDetector> detectors;

	private final List<Analyzer> analyzers;

	private final AnalyzerSettings settings;

	@Autowired
	public AnalyzerRegistry(ObjectProvider<TechnologyDetector> detectors, ObjectProvider<Analyzer> analyzers,
			AnalyzerSettings settings) {
		this(detectors.orderedStream().toList(), analyzers.orderedStream().toList(), settings);
	}

	public AnalyzerRegistry(List<TechnologyDetector> detectors, List<Analyzer> analyzers, AnalyzerSettings settings) {
		this.settings = settings;
		this.detectors = detectors.stream().sorted(Comparator.comparing(d -> d.descriptor().id())).toList();
		this.analyzers = analyzers.stream().sorted(Comparator.comparing(a -> a.descriptor().id())).toList();
		validate();
		log.info("Registered {} detector(s) and {} analyzer(s)", this.detectors.size(), this.analyzers.size());
	}

	public List<TechnologyDetector> detectors() {
		return detectors;
	}

	public List<Analyzer> analyzers() {
		return analyzers;
	}

	public Analyzer analyzer(String id) {
		return analyzers.stream()
			.filter(a -> a.descriptor().id().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown analyzer: " + id));
	}

	public Map<AnalysisCategory, List<Analyzer>> byCategory() {
		Map<AnalysisCategory, List<Analyzer>> grouped = new EnumMap<>(AnalysisCategory.class);
		for (Analyzer analyzer : analyzers) {
			grouped.computeIfAbsent(analyzer.descriptor().category(), c -> new java.util.ArrayList<>()).add(analyzer);
		}
		return grouped;
	}

	public boolean isEnabled(String id) {
		return settings.isEnabled(id);
	}

	/**
	 * Evaluates every analyzer for a snapshot, in isolation: a disabled analyzer is {@code DISABLED}
	 * without being asked, and an analyzer whose applicability check throws is {@code UNAVAILABLE}
	 * rather than aborting the run.
	 */
	public Map<String, Applicability> evaluate(AnalysisContext context) {
		Map<String, Applicability> result = new LinkedHashMap<>();
		for (Analyzer analyzer : analyzers) {
			String id = analyzer.descriptor().id();
			if (!settings.isEnabled(id)) {
				result.put(id, Applicability.disabled());
				continue;
			}
			try {
				Applicability applicability = analyzer.applicability(context);
				result.put(id, applicability != null ? applicability
						: Applicability.unavailable("the applicability check returned no result"));
			}
			catch (RuntimeException ex) {
				log.warn("Applicability check of analyzer {} failed: {}", id, ex.getClass().getSimpleName());
				result.put(id, Applicability.unavailable("the applicability check failed (" + ex.getClass().getSimpleName() + ")"));
			}
		}
		return result;
	}

	/**
	 * SHA-256 of the sorted {@code id@version} list of every enabled detector and analyzer. It changes
	 * when an analyzer is added, removed, enabled, disabled or upgraded, which invalidates reuse of
	 * older completed runs.
	 */
	public String analyzerSetHash() {
		String joined = java.util.stream.Stream
			.concat(detectors.stream().map(TechnologyDetector::descriptor), analyzers.stream().map(Analyzer::descriptor))
			.filter(descriptor -> settings.isEnabled(descriptor.id()))
			.map(AnalyzerDescriptor::idAtVersion)
			.sorted()
			.collect(Collectors.joining("\n"));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public String currentHash() {
		return analyzerSetHash();
	}

	/** Fails fast at startup on a misconfigured pack instead of misbehaving during an analysis. */
	private void validate() {
		Set<String> ids = new HashSet<>();
		for (TechnologyDetector detector : detectors) {
			AnalyzerDescriptor descriptor = detector.descriptor();
			require(ids.add(descriptor.id()), "Duplicate analyzer id: " + descriptor.id());
			require(descriptor.category() == AnalysisCategory.TECHNOLOGY,
					"Detector " + descriptor.id() + " must have category TECHNOLOGY, not " + descriptor.category());
		}
		for (Analyzer analyzer : analyzers) {
			AnalyzerDescriptor descriptor = analyzer.descriptor();
			require(ids.add(descriptor.id()), "Duplicate analyzer id: " + descriptor.id());
			require(descriptor.category() != AnalysisCategory.TECHNOLOGY,
					"Analyzer " + descriptor.id() + " cannot have category TECHNOLOGY; implement TechnologyDetector");
			AnalysisCategory expected = expectedCategory(analyzer);
			require(expected == null || expected == descriptor.category(), "Analyzer " + descriptor.id() + " implements "
					+ "an interface for " + expected + " but declares category " + descriptor.category());
		}
	}

	private static AnalysisCategory expectedCategory(Analyzer analyzer) {
		if (analyzer instanceof RepositoryAnalyzer) {
			return AnalysisCategory.STRUCTURE;
		}
		if (analyzer instanceof DependencyAnalyzer) {
			return AnalysisCategory.DEPENDENCIES;
		}
		if (analyzer instanceof CodeAnalyzer) {
			return AnalysisCategory.CODE_QUALITY;
		}
		if (analyzer instanceof SecurityAnalyzer) {
			return AnalysisCategory.SECURITY;
		}
		if (analyzer instanceof TestAnalyzer) {
			return AnalysisCategory.TESTS;
		}
		return null;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
	}

}
