package com.repointelligence.analysis.spi;

/**
 * Inventories dependencies for a supported package or build ecosystem. Its descriptor's category must be
 * {@code DEPENDENCIES}; the registry checks this at startup.
 */
public interface DependencyAnalyzer extends Analyzer {

}
