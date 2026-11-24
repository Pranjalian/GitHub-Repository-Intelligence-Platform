package com.repointelligence.impact.api;

import com.repointelligence.impact.application.ImpactAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Change-impact analysis of one snapshot (MVP 5). The snapshot must belong to the repository. */
@Tag(name = "Impact analysis", description = "What may be affected by changing a class or method (MVP 5).")
@RestController
public class ImpactController {

	private final ImpactAnalysisService service;

	public ImpactController(ImpactAnalysisService service) {
		this.service = service;
	}

	@Operation(summary = "Impact of changing one symbol",
			description = "Follows the stored dependency graph (AST facts, never a model) from the target to its callers and "
					+ "dependents to the requested depth, and returns affected components with evidence and confidence, "
					+ "endpoints, related entities and repositories, modules, tests, suggested regression areas, a heuristic "
					+ "risk score with its factors, and the limitations of the analysis. includeAiSummary=true adds an optional, "
					+ "clearly marked model summary; it never changes the rest. 404 when the symbol is not in the latest "
					+ "completed run of the snapshot.")
	@PostMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/impact-analysis")
	public ImpactAnalysisDTO analyze(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@Valid @RequestBody ImpactAnalysisRequestDTO request) {
		return service.analyze(repositoryId, snapshotId, request);
	}

	@Operation(summary = "Symbols that can be picked as an impact target",
			description = "Deterministic, model-free search over the snapshot's classes, interfaces, methods and constructors by "
					+ "simple name, qualified name or Type.method.")
	@GetMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/impact-analysis/symbols")
	public SymbolPickerDTO symbols(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "25") int limit) {
		return service.symbols(repositoryId, snapshotId, q, Math.max(1, Math.min(limit, 100)));
	}

	@Operation(summary = "Suggest impact targets for a change description (AI)",
			description = "Maps free text to symbols of the snapshot with a model. Only symbols found by name in the snapshot can be "
					+ "suggested and the person confirms one before it is analysed. status=UNAVAILABLE without a permitted AI provider.")
	@PostMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/impact-analysis/suggestions")
	public TargetSuggestionDTO suggest(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@Valid @RequestBody TargetSuggestionRequestDTO request) {
		return service.suggestTargets(repositoryId, snapshotId, request.changeDescription(), request.provider());
	}

}
