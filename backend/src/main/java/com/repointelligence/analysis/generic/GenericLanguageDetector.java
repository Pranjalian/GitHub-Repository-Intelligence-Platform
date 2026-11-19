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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Detects programming languages by analysing the file-language distribution already computed by
 * {@link com.repointelligence.repository.application.FileClassifier}. Binary files and documentation
 * files are excluded from the totals so that large asset directories do not distort the picture.
 *
 * <p>Languages above a configurable byte-share threshold (default 2 %) are reported as
 * {@link Confidence#LIKELY}; the threshold prevents trivial vendored files from appearing.
 */
@Component
public class GenericLanguageDetector implements TechnologyDetector {

	static final String ID = "generic-language-detector";

	private static final String VERSION = "1.0.0";

	/** Languages below this share (0–100) are suppressed. Configurable via AnalyzerSettings. */
	private static final double DEFAULT_MIN_SHARE_PERCENT = 2.0;

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Generic Language Detector", VERSION, AnalysisCategory.TECHNOLOGY, CapabilityLevel.GENERIC)
		.description("Detects programming languages from file-extension statistics. "
				+ "Excludes binary and documentation files. Reports languages above a configurable share threshold.")
		.build();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public List<DetectedTechnology> detect(AnalysisContext context) {
		double minShare = minSharePercent(context);

		// Aggregate bytes per language, skipping binary and docs.
		Map<String, long[]> langBytes = new LinkedHashMap<>();
		long totalBytes = 0;

		for (FileInfo file : context.files()) {
			if (file.binary() || file.skippedReason() != null) {
				continue;
			}
			if (com.repointelligence.repository.domain.FileType.DOCS == file.fileType()) {
				continue;
			}
			String lang = file.language();
			if (lang == null || lang.isBlank()) {
				continue;
			}
			langBytes.computeIfAbsent(lang, k -> new long[1])[0] += file.sizeBytes();
			totalBytes += file.sizeBytes();
		}

		if (totalBytes == 0) {
			return List.of();
		}

		List<DetectedTechnology> results = new ArrayList<>();
		final long total = totalBytes;

		for (Map.Entry<String, long[]> entry : langBytes.entrySet()) {
			long langTotal = entry.getValue()[0];
			double share = 100.0 * langTotal / total;
			if (share < minShare) {
				continue;
			}
			BigDecimal sharePercent = BigDecimal.valueOf(share).setScale(1, RoundingMode.HALF_UP);
			Evidence evidence = new Evidence(null, null,
					langTotal + " bytes (" + sharePercent + "% of analysed source)");
			results.add(new DetectedTechnology(entry.getKey(), TechnologyKind.LANGUAGE, null, Confidence.LIKELY,
					List.of(evidence), ID, sharePercent));
		}

		// Return in descending share order so the primary language comes first.
		results.sort(Comparator.comparing(DetectedTechnology::sharePercent,
				Comparator.nullsLast(Comparator.reverseOrder())));
		return List.copyOf(results);
	}

	private static double minSharePercent(AnalysisContext context) {
		if (context.settings() == null) {
			return DEFAULT_MIN_SHARE_PERCENT;
		}
		double parsed = context.settings().intOption(ID, "min-share-percent", (int) DEFAULT_MIN_SHARE_PERCENT);
		return parsed <= 0 ? DEFAULT_MIN_SHARE_PERCENT : parsed;
	}

}
