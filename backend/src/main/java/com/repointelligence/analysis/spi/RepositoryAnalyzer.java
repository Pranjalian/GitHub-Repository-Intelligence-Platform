package com.repointelligence.analysis.spi;

/**
 * Generic or technology-specific repository facts: file tree, modules, manifests. Its descriptor's category must be
 * {@code STRUCTURE}; the registry checks this at startup.
 */
public interface RepositoryAnalyzer extends Analyzer {

}
