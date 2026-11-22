package com.repointelligence.ai.application;

import com.repointelligence.ai.domain.AIUsageRecord;
import com.repointelligence.ai.domain.CostSource;
import com.repointelligence.ai.infrastructure.persistence.AIUsageRecordRepository;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.UsageSource;
import com.repointelligence.common.security.SecretRedactor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes one usage record per provider call and per cache hit, including failures. Reported counts are
 * kept apart from the local estimate; cost only when pricing for the provider is configured.
 */
@Service
public class AIUsageService {

	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

	private final AIUsageRecordRepository records;

	private final AIProperties properties;

	private final Clock clock;

	public AIUsageService(AIUsageRecordRepository records, AIProperties properties, Clock clock) {
		this.records = records;
		this.properties = properties;
		this.clock = clock;
	}

	/** One finished call (or cache hit) and its numbers. */
	public record Call(String provider, String model, String feature, String promptVersion, UUID repositoryId,
			UUID snapshotId, UUID runId, AIStatus status, boolean cacheHit, Integer inputTokens, Integer outputTokens,
			int estimatedInputTokens, Integer estimatedOutputTokens, Long latencyMs, String detail) {
	}

	public AIUsageRecord record(Call call) {
		boolean reported = call.inputTokens() != null || call.outputTokens() != null;
		UsageSource source = reported ? UsageSource.REPORTED : call.cacheHit() ? UsageSource.UNKNOWN : UsageSource.ESTIMATED;
		AIUsageRecord record = new AIUsageRecord(clock.instant(), call.provider(), call.model(), call.feature(),
				call.promptVersion(), call.status(), call.cacheHit(), source);
		record.setScope(call.repositoryId(), call.snapshotId(), call.runId());
		record.setReportedTokens(call.inputTokens(), call.outputTokens());
		record.setEstimatedTokens(call.cacheHit() ? null : call.estimatedInputTokens(), call.estimatedOutputTokens());
		record.setLatencyMs(call.latencyMs());
		record.setDetail(call.detail() == null ? null : SecretRedactor.redact(call.detail()));
		if (!call.cacheHit()) {
			applyCost(record, call, reported);
		}
		return records.save(record);
	}

	/** Provider calls already made against a run's budget (cache hits are free). */
	public long callsForRun(UUID runId) {
		return runId == null ? 0 : records.countByRunIdAndCacheHitFalse(runId);
	}

	private void applyCost(AIUsageRecord record, Call call, boolean reported) {
		AIProperties.ProviderSettings settings = properties.provider(call.provider());
		if (settings == null || !settings.pricingConfigured()) {
			return;
		}
		Integer in = reported ? call.inputTokens() : Integer.valueOf(call.estimatedInputTokens());
		Integer out = reported ? call.outputTokens() : call.estimatedOutputTokens();
		if (in == null && out == null) {
			return;
		}
		BigDecimal cost = BigDecimal.valueOf(in == null ? 0 : in)
			.multiply(settings.inputPricePerMillionTokens())
			.add(BigDecimal.valueOf(out == null ? 0 : out).multiply(settings.outputPricePerMillionTokens()))
			.divide(MILLION, 6, RoundingMode.HALF_UP);
		record.setCost(cost, reported ? CostSource.REPORTED_TOKENS : CostSource.ESTIMATED_TOKENS, settings.currency());
	}

}
