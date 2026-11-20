package com.repointelligence.analysis.api;

import com.repointelligence.analysis.application.AnalysisRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Analysis runs", description = "Progress and cancellation of analysis runs.")
@RestController
@RequestMapping("/api/v1/analysis-runs")
public class AnalysisRunController {

	private final AnalysisRunService service;

	public AnalysisRunController(AnalysisRunService service) {
		this.service = service;
	}

	@Operation(summary = "Status of a run, with the status of every analyzer")
	@GetMapping("/{runId}")
	public AnalysisRunDTO get(@PathVariable UUID runId) {
		return service.get(runId);
	}

	@Operation(summary = "Cancel a queued or running analysis")
	/** 202: cancellation was requested (a running run ends as CANCELLED shortly after). 409 if it already ended. */
	@PostMapping("/{runId}/cancel")
	public ResponseEntity<AnalysisRunDTO> cancel(@PathVariable UUID runId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.cancel(runId));
	}

}
