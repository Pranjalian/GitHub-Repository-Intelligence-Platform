package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.common.model.Confidence;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** A technology found by a detector, with the evidence for it. Detection is not analyzer support. */
public record DetectedTechnology(String technology, TechnologyKind kind, String version, Confidence confidence,
		List<Evidence> evidence, String detectorId, BigDecimal sharePercent) {

	public DetectedTechnology {
		Objects.requireNonNull(technology, "technology");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(confidence, "confidence");
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
	}

}
