package com.repointelligence.analysis.spi;

/**
 * The contract every analyzer implements. Analyzers return results; only the orchestrator persists
 * them. A failure, timeout or unsupported feature in one analyzer never affects another.
 */
public interface Analyzer {

	AnalyzerDescriptor descriptor();

	/** Cheap check of whether this analyzer can and should run for the snapshot. */
	Applicability applicability(AnalysisContext context);

	/** Runs the analysis. Long work must check {@code context.cancellation()} and the deadline. */
	AnalyzerResult analyze(AnalysisContext context) throws Exception;

}
