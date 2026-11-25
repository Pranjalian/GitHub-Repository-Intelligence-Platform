package com.repointelligence.repository.api;

import com.repointelligence.repository.application.RepositoryQueryService;
import com.repointelligence.repository.application.RepositorySubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Repositories", description = "Submit public GitHub repositories and read their snapshots.")
@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

	private final RepositorySubmissionService submissionService;

	private final RepositoryQueryService queryService;

	public RepositoryController(RepositorySubmissionService submissionService, RepositoryQueryService queryService) {
		this.submissionService = submissionService;
		this.queryService = queryService;
	}

	@Operation(summary = "Submit a repository for analysis",
			description = "Resolves the repository and its exact commit, then queues an analysis run. Returns the run id to poll.")
	@ApiResponse(responseCode = "202", description = "A new run was queued")
	@ApiResponse(responseCode = "200", description = "An existing run for this commit was reused")
	@ApiResponse(responseCode = "400", description = "The URL is not a supported GitHub repository URL")
	@ApiResponse(responseCode = "404", description = "The repository does not exist or is private")
	@ApiResponse(responseCode = "422", description = "The repository exceeds the size limits")
	@ApiResponse(responseCode = "429", description = "Rate limit or job cap reached; see Retry-After")
	/** 202 when a new run was queued; 200 when an existing run is returned ({@code reused: true}). */
	@PostMapping("/analyze")
	public ResponseEntity<AnalyzeRepositoryResponse> analyze(@Valid @RequestBody AnalyzeRepositoryRequest request) {
		AnalyzeRepositoryResponse response = submissionService.submit(request.url(), request.forceRescan());
		return ResponseEntity.status(response.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
	}

	@Operation(summary = "List analyzed repositories")
	@GetMapping
	public PageResponse<RepositoryDTO> list(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return queryService.list(page, size);
	}

	@Operation(summary = "Dashboard KPI tiles",
			description = "Repositories analyzed, indexed for search, open findings of the analyzed categories (null when none was analyzed) and active runs.")
	@GetMapping("/stats")
	public RepositoryStatsDTO stats() {
		return queryService.stats();
	}

	@Operation(summary = "Get one repository with its latest snapshot")
	@GetMapping("/{repositoryId}")
	public RepositoryDTO get(@PathVariable UUID repositoryId) {
		return queryService.get(repositoryId);
	}

	@Operation(summary = "List the analyzed commits (snapshots) of a repository, newest first")
	@GetMapping("/{repositoryId}/snapshots")
	public PageResponse<SnapshotDTO> snapshots(@PathVariable UUID repositoryId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return queryService.snapshots(repositoryId, page, size);
	}

}
