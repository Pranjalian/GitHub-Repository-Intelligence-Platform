package com.repointelligence.rag.chunking;

/**
 * One repository file to chunk. {@code text} is already secret-scrubbed: chunkers never see a secret.
 *
 * @param fileType {@code SOURCE}, {@code TEST}, {@code CONFIG}, {@code BUILD} or {@code DOCS}
 */
public record SourceFile(String path, String language, String fileType, String modulePath, String text) {
}
