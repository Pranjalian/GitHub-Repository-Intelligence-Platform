package com.repointelligence.repository.infrastructure.storage;

import java.util.List;

/** Limits enforced while the archive is streamed. Byte values are plain bytes. */
public record ExtractionLimits(long maxFileBytes, long maxTotalBytes, int maxFileCount, int maxEntries,
		List<String> excludedDirectories, List<String> excludedExceptions) {
}
