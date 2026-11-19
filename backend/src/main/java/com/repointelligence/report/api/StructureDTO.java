package com.repointelligence.report.api;

import java.util.List;
import java.util.Map;

/**
 * The structure of the repository at this commit. {@code summary} is the structure analyzer's summary
 * (file-type counts, size statistics, extension distribution, directory tree) and is null when
 * structure analysis did not complete.
 */
public record StructureDTO(List<ModuleDTO> modules, List<String> entryPoints, Map<String, Object> summary) {
}
