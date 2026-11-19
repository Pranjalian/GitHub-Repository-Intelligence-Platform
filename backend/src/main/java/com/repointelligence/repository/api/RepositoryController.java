package com.repointelligence.repository.api;

import com.repointelligence.repository.application.RepositoryQueryService;
import com.repointelligence.repository.application.RepositorySubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

	private final RepositorySubmissionService submissionService;

	private final RepositoryQueryService queryService;

	public RepositoryController(RepositorySubmissionService submissionService, RepositoryQueryService queryService) {
		this.submissionService = submissionService;
		this.queryService = queryService;
	}

	/** 202 when a new run was queued; 200 when an existing run is returned ({@code reused: true}). */
	@PostMapping("/analyze")
	public ResponseEntity<AnalyzeRepositoryResponse> analyze(@Valid @RequestBody AnalyzeRepositoryRequest request) {
		AnalyzeRepositoryResponse response = submissionService.submit(request.url(), request.forceRescan());
		return ResponseEntity.status(response.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
	}

	@GetMapping
	public PageResponse<RepositoryDTO> list(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return queryService.list(page, size);
	}

	@GetMapping("/{repositoryId}")
	public RepositoryDTO get(@PathVariable UUID repositoryId) {
		return queryService.get(repositoryId);
	}

	@GetMapping("/{repositoryId}/snapshots")
	public PageResponse<SnapshotDTO> snapshots(@PathVariable UUID repositoryId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return queryService.snapshots(repositoryId, page, size);
	}

}
