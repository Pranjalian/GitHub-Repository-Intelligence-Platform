package com.repointelligence.analysis.application;

/**
 * Hash of the sorted analyzer {@code id@version} list currently registered. A completed run can only
 * be reused when its stored hash equals the current one. The analyzer registry (Step 1.6) supplies
 * the real implementation.
 */
@FunctionalInterface
public interface AnalyzerSetHashProvider {

	String currentHash();

}
