package com.repointelligence.analysis.domain;

/** One piece of evidence: a file, optional line and the signal that was observed. */
public record Evidence(String path, Integer line, String signal) {
}
