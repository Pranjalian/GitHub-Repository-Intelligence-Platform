package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.common.model.Confidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges what several detectors reported about the same technology: the strongest confidence wins,
 * evidence is combined, and the first known version and share are kept.
 */
final class TechnologyMerger {

	private TechnologyMerger() {
	}

	static List<DetectedTechnology> merge(List<DetectedTechnology> detections) {
		Map<String, DetectedTechnology> merged = new LinkedHashMap<>();
		for (DetectedTechnology detection : detections) {
			String key = detection.kind() + ":" + detection.technology().toLowerCase(Locale.ROOT);
			merged.merge(key, detection, TechnologyMerger::combine);
		}
		return new ArrayList<>(merged.values());
	}

	private static DetectedTechnology combine(DetectedTechnology a, DetectedTechnology b) {
		DetectedTechnology stronger = strength(a.confidence()) <= strength(b.confidence()) ? a : b;
		LinkedHashSet<Evidence> evidence = new LinkedHashSet<>(a.evidence());
		evidence.addAll(b.evidence());
		return new DetectedTechnology(stronger.technology(), stronger.kind(),
				a.version() != null ? a.version() : b.version(), stronger.confidence(), List.copyOf(evidence),
				stronger.detectorId(), a.sharePercent() != null ? a.sharePercent() : b.sharePercent());
	}

	/** Lower is stronger: DETECTED, LIKELY, POSSIBLE, NOT_VERIFIED. */
	private static int strength(Confidence confidence) {
		return confidence.ordinal();
	}

}
