package com.repointelligence.analysis.spi;

/**
 * Language-aware static analysis and code-quality checks. Its descriptor's category must be
 * {@code CODE_QUALITY}; the registry checks this at startup.
 */
public interface CodeAnalyzer extends Analyzer {

}
