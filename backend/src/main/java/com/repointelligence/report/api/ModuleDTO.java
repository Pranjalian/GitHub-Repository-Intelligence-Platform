package com.repointelligence.report.api;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.Evidence;
import java.util.List;

public record ModuleDTO(String name, String path, String buildFilePath, Ecosystem ecosystem, String responsibility,
		List<Evidence> evidence) {
}
