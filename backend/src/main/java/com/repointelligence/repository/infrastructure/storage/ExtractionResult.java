package com.repointelligence.repository.infrastructure.storage;

import java.util.List;

public record ExtractionResult(List<ExtractedEntry> entries, long totalDecompressedBytes, int excludedEntries) {
}
