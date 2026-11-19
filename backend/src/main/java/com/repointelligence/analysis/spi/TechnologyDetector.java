package com.repointelligence.analysis.spi;

import java.util.List;

/** Detects languages, frameworks, build tools and versions from repository evidence. */
public interface TechnologyDetector {

	/** Category {@code TECHNOLOGY}. */
	AnalyzerDescriptor descriptor();

	List<DetectedTechnology> detect(AnalysisContext context) throws Exception;

}
