package com.repointelligence.report.api;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.common.model.Confidence;
import java.math.BigDecimal;
import java.util.List;

public record TechnologyDTO(String name, TechnologyKind kind, String version, Confidence confidence,
		BigDecimal sharePercent, String detectorId, List<Evidence> evidence) {
}
