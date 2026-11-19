package com.repointelligence.analysis.spi;

/**
 * Security rules, secret detection and vulnerability results; reports actual coverage. Its descriptor's category must be
 * {@code SECURITY}; the registry checks this at startup.
 */
public interface SecurityAnalyzer extends Analyzer {

}
