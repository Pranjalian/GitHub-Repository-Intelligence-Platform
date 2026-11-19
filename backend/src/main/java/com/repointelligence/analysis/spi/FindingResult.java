package com.repointelligence.analysis.spi;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import java.util.Map;
import java.util.Objects;

/**
 * A finding as reported by an analyzer. Evidence must already be safe to store, but the orchestrator
 * still redacts it, caps its length and computes the fingerprint before persisting.
 */
public record FindingResult(AnalysisCategory category, String ruleId, Severity severity, Confidence confidence,
		String title, String description, String filePath, String modulePath, Integer startLine, Integer endLine,
		Map<String, Object> evidence, AnalysisMethod analysisMethod, String suggestedNextStep) {

	public FindingResult {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(ruleId, "ruleId");
		Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(confidence, "confidence");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(analysisMethod, "analysisMethod");
		evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
	}

}
