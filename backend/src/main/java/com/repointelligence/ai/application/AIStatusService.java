package com.repointelligence.ai.application;

import com.repointelligence.ai.api.AIProvidersDTO;
import com.repointelligence.ai.api.AIUsageRecordDTO;
import com.repointelligence.ai.domain.AIUsageRecord;
import com.repointelligence.ai.infrastructure.persistence.AIUsageRecordRepository;
import com.repointelligence.ai.provider.AIModelService;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderCapabilities;
import com.repointelligence.ai.provider.AIProviderRouter;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.DataHandling;
import com.repointelligence.ai.provider.ProviderCatalog;
import com.repointelligence.ai.provider.ProviderHealth;
import com.repointelligence.ai.provider.RoutingContext;
import com.repointelligence.ai.provider.RoutingDecision;
import com.repointelligence.repository.api.PageResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/** Read side of the AI settings page: providers with health and capabilities, and usage records. */
@Service
public class AIStatusService {

	private final AIProperties properties;

	private final AIProviderRouter router;

	private final AIUsageRecordRepository usage;

	private final Clock clock;

	public AIStatusService(AIProperties properties, AIProviderRouter router, AIUsageRecordRepository usage, Clock clock) {
		this.properties = properties;
		this.router = router;
		this.usage = usage;
		this.clock = clock;
	}

	public AIProvidersDTO providers() {
		Set<String> ids = new LinkedHashSet<>();
		ProviderCatalog.ids().forEach(ids::add);
		ids.addAll(properties.providers().keySet());
		List<AIProvidersDTO.ProviderDTO> providers = new ArrayList<>();
		for (String id : ids) {
			AIProperties.ProviderSettings settings = properties.provider(id);
			AIModelService service = router.service(id);
			DataHandling handling = service != null ? service.dataHandling() : ProviderCatalog.dataHandling(id);
			boolean acknowledged = settings != null && settings.dataHandlingAcknowledged();
			ProviderHealth health = service != null ? router.availability().health(service)
					: new ProviderHealth(ProviderHealth.Status.UNKNOWN,
							properties.enabled() ? "Provider disabled (ai.providers." + id + ".enabled=false)."
									: "AI is disabled (ai.enabled=false).",
							clock.instant(), null);
			AIProviderCapabilities capabilities = service != null ? service.getCapabilities() : null;
			providers.add(new AIProvidersDTO.ProviderDTO(id, settings != null && settings.enabled(), service != null,
					handling, handling == DataHandling.LOCAL || acknowledged, service != null && router.permitted(id, true),
					settings == null ? null : settings.chatModel(),
					settings != null && settings.apiKey() != null && !settings.apiKey().isBlank(), health, capabilities,
					settings != null && settings.pricingConfigured(), settings == null ? null : settings.currency()));
		}
		RoutingDecision decision = router.route(new RoutingContext(null, null, true));
		List<AIProvidersDTO.FeatureRouteDTO> features = properties.features()
			.entrySet()
			.stream()
			.sorted(java.util.Map.Entry.comparingByKey())
			.map(e -> new AIProvidersDTO.FeatureRouteDTO(e.getKey(), e.getValue().provider(), e.getValue().model()))
			.toList();
		AIProperties.Limits limits = properties.limits();
		return new AIProvidersDTO(properties.enabled(), decision.routed(),
				decision.routed() ? null : decision.unavailableReason(), properties.defaultProvider(),
				properties.fallbackProviders(), blankToNull(properties.setupGuideUrl()),
				new AIProvidersDTO.LimitsDTO(limits.maxInputTokens(), limits.maxOutputTokens(), limits.maxCallsPerAnalysis(),
						limits.timeoutSeconds(), limits.maxRetries(), properties.tokenEstimate().charsPerToken()),
				new AIProvidersDTO.CacheDTO(properties.cache().enabled(), properties.cache().ttl()), features, providers);
	}

	public PageResponse<AIUsageRecordDTO> usage(String provider, String feature, AIStatus status, UUID repositoryId,
			UUID snapshotId, Boolean cacheHit, int page, int size) {
		Specification<AIUsageRecord> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (provider != null && !provider.isBlank()) {
				predicates.add(cb.equal(root.get("provider"), provider));
			}
			if (feature != null && !feature.isBlank()) {
				predicates.add(cb.equal(root.get("feature"), feature));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (repositoryId != null) {
				predicates.add(cb.equal(root.get("repositoryId"), repositoryId));
			}
			if (snapshotId != null) {
				predicates.add(cb.equal(root.get("snapshotId"), snapshotId));
			}
			if (cacheHit != null) {
				predicates.add(cb.equal(root.get("cacheHit"), cacheHit));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return PageResponse.from(usage.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
				AIStatusService::toDto);
	}

	private static AIUsageRecordDTO toDto(AIUsageRecord r) {
		return new AIUsageRecordDTO(r.getId(), r.getCreatedAt(), r.getProvider(), r.getModel(), r.getFeature(),
				r.getPromptVersion(), r.getRepositoryId(), r.getSnapshotId(), r.getRunId(), r.getStatus(), r.isCacheHit(),
				r.getInputTokens(), r.getOutputTokens(), r.getEstimatedInputTokens(), r.getEstimatedOutputTokens(),
				r.getUsageSource(), r.getEstimatedCost(), r.getCostSource(), r.getCurrency(), r.getLatencyMs(), r.getDetail());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

}
