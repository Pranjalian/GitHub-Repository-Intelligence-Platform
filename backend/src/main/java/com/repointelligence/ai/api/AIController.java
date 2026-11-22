package com.repointelligence.ai.api;

import com.repointelligence.ai.application.AIStatusService;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.repository.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI configuration and usage (MVP 3). Works with AI disabled: it then says so. */
@Tag(name = "AI", description = "Optional AI providers, their health and data handling, limits and usage.")
@RestController
@RequestMapping("/api/v1/ai")
public class AIController {

	private final AIStatusService service;

	public AIController(AIStatusService service) {
		this.service = service;
	}

	@Operation(summary = "Configured AI providers",
			description = "Providers with health, data handling (LOCAL or HOSTED), acknowledgement, capabilities and "
					+ "pricing flag; limits, cache and feature routes. API keys are never returned. Enabled providers "
					+ "are health-checked (cached for ai.health-cache-ttl).")
	@GetMapping("/providers")
	public AIProvidersDTO providers() {
		return service.providers();
	}

	@Operation(summary = "AI usage records",
			description = "Newest first. Token counts are null unless the provider reported them (usageSource REPORTED); "
					+ "estimated counts are labelled. Cost is null unless pricing is configured.")
	@GetMapping("/usage")
	public PageResponse<AIUsageRecordDTO> usage(@RequestParam(required = false) String provider,
			@RequestParam(required = false) String feature, @RequestParam(required = false) AIStatus status,
			@RequestParam(required = false) UUID repositoryId, @RequestParam(required = false) UUID snapshotId,
			@RequestParam(required = false) Boolean cacheHit, @RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
		return service.usage(provider, feature, status, repositoryId, snapshotId, cacheHit, page, size);
	}

}
