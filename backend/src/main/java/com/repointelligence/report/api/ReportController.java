package com.repointelligence.report.api;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.api.PageResponse;
import com.repointelligence.report.application.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Commit-scoped report endpoints: every one names the snapshot, and the snapshot must belong to the repository. */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}")
public class ReportController {

	private final ReportService service;

	public ReportController(ReportService service) {
		this.service = service;
	}

	@GetMapping("/reports/{snapshotId}")
	public ConsolidatedReportDTO report(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@RequestParam(required = false) UUID runId) {
		return service.generate(repositoryId, snapshotId, runId);
	}

	@GetMapping("/snapshots/{snapshotId}/findings")
	public PageResponse<FindingDTO> findings(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@RequestParam(required = false) AnalysisCategory category,
			@RequestParam(required = false) Severity severity, @RequestParam(required = false) String module,
			@RequestParam(required = false) String file, @RequestParam(required = false) String sort,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return service.findings(repositoryId, snapshotId, category, severity, module, file, sort, page, size);
	}

	@GetMapping("/snapshots/{snapshotId}/dependencies")
	public PageResponse<DependencyDTO> dependencies(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@RequestParam(required = false) Ecosystem ecosystem, @RequestParam(required = false) String module,
			@RequestParam(required = false) String scope, @RequestParam(required = false) Boolean vulnerable,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
		return service.dependencies(repositoryId, snapshotId, ecosystem, module, scope, vulnerable, page, size);
	}

	@GetMapping("/snapshots/{snapshotId}/tests")
	public TestsReportDTO tests(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId) {
		return service.tests(repositoryId, snapshotId);
	}

	@GetMapping("/snapshots/{snapshotId}/coverage")
	public CoverageReportDTO coverage(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId) {
		return service.coverage(repositoryId, snapshotId);
	}

}
