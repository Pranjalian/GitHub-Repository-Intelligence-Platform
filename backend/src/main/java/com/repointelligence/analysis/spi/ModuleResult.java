package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.Evidence;
import java.util.List;
import java.util.Objects;

public record ModuleResult(String moduleName, String path, String buildFilePath, Ecosystem ecosystem,
		String detectedResponsibility, List<Evidence> evidence) {

	public ModuleResult {
		Objects.requireNonNull(moduleName, "moduleName");
		Objects.requireNonNull(path, "path");
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
	}

}
