package com.repointelligence.analysis.spi;

/**
 * Discovers how an application is organised: symbols, relationships, layers, endpoints, entities and
 * pattern candidates, returned in {@link AnalyzerResult#architecture()}. Its descriptor's category must be
 * {@code ARCHITECTURE}; the registry checks this at startup.
 */
public interface ArchitectureAnalyzer extends Analyzer {

}
