package com.repointelligence.analysis.spi;

/**
 * Discovers test frameworks and parses supported test and coverage reports. Its descriptor's category must be
 * {@code TESTS}; the registry checks this at startup.
 */
public interface TestAnalyzer extends Analyzer {

}
