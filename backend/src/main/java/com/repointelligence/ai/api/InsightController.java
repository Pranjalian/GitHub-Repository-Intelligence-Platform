package com.repointelligence.ai.api;

import com.repointelligence.ai.application.InsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Optional AI insights on one snapshot. AI disabled is not an error: 200 with status UNAVAILABLE. */
@Tag(name = "AI insights", description = "AI-generated explanations grounded in the snapshot's analysis (MVP 3).")
@RestController
public class InsightController {

	private final InsightService service;

	public InsightController(InsightService service) {
		this.service = service;
	}

	@Operation(summary = "Generate an AI insight for a snapshot",
			description = "Explains a class, method or finding, suggests refactorings, summarises a module or the "
					+ "technical debt. The answer is marked aiGenerated and lists the evidence that was sent (secrets "
					+ "scrubbed). 200 with status UNAVAILABLE (and a reason) when AI is disabled or no permitted provider "
					+ "is available; SKIPPED when the call budget is used up; 404 when the snapshot or target does not "
					+ "belong to the repository's latest completed analysis.")
	@PostMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/insights")
	public InsightDTO insight(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@Valid @RequestBody InsightRequestDTO request) {
		return service.insight(repositoryId, snapshotId, request);
	}

}
