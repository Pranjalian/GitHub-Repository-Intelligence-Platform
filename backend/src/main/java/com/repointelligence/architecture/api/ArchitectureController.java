package com.repointelligence.architecture.api;

import com.repointelligence.architecture.application.ArchitectureReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The architecture report of one snapshot; the snapshot must belong to the repository. */
@Tag(name = "Architecture", description = "Commit-scoped architecture discovery (MVP 2).")
@RestController
public class ArchitectureController {

	private final ArchitectureReportService service;

	private final com.repointelligence.architecture.application.SymbolQueryService symbols;

	public ArchitectureController(ArchitectureReportService service,
			com.repointelligence.architecture.application.SymbolQueryService symbols) {
		this.service = service;
		this.symbols = symbols;
	}

	@Operation(summary = "A code symbol of a snapshot with its members",
			description = "Types list their methods, constructors, fields and nested types. Ids come from the "
					+ "architecture report (layers[].classes[].symbolId) and are used for AI insights. 404 unless the "
					+ "symbol belongs to the latest completed run of the snapshot.")
	@GetMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/symbols/{symbolId}")
	public SymbolDTO symbol(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@PathVariable UUID symbolId) {
		return symbols.symbol(repositoryId, snapshotId, symbolId);
	}

	@Operation(summary = "Architecture of a snapshot",
			description = "Layers, REST endpoints, JPA entities, dependency injection and chains, Maven module "
					+ "dependencies, cycles, pattern candidates and Mermaid diagrams, with confidence, evidence and "
					+ "limitations. Uses the latest completed run unless runId is given. supported=false (and empty lists) "
					+ "when no architecture analyzer exists for the detected technologies.")
	@GetMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/architecture")
	public ArchitectureReportDTO architecture(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@RequestParam(required = false) UUID runId) {
		return service.report(repositoryId, snapshotId, runId);
	}

}
