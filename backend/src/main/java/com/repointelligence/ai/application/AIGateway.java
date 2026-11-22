package com.repointelligence.ai.application;

import com.repointelligence.ai.domain.AICacheEntry;
import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.prompt.PromptAssembler;
import com.repointelligence.ai.prompt.PromptTemplate;
import com.repointelligence.ai.provider.AIModelService;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderRouter;
import com.repointelligence.ai.provider.AIRequest;
import com.repointelligence.ai.provider.AIResponse;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.RoutingContext;
import com.repointelligence.ai.provider.RoutingDecision;
import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.generic.GenericSecretScanAnalyzer;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.security.SecretRedactor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * The single entry point for AI features. Every request passes the guardrails of architecture §7.2 in
 * this order:
 * <ol>
 * <li>AI enabled?</li>
 * <li>a permitted, healthy provider (router: preference, feature route, default, permitted fallbacks)</li>
 * <li>secret scrub of every data block ({@link PromptScrubber})</li>
 * <li>bounded prompt: evidence trimmed to {@code ai.limits.max-input-tokens} (and the model's context)</li>
 * <li>cache lookup (a hit costs no call, so it is served even when the budget is used up)</li>
 * <li>call budget per analysis run ({@code ai.limits.max-calls-per-analysis})</li>
 * <li>provider call with the timeout and retry limit (inside the adapter)</li>
 * <li>schema validation; invalid answers become INVALID_OUTPUT and are never shown</li>
 * <li>usage record (also for failures and cache hits)</li>
 * <li>cache store of valid answers</li>
 * <li>the result, marked AI-generated when it has content</li>
 * </ol>
 * Never throws for AI failures: every outcome is a status with a reason.
 */
@Service
public class AIGateway {

	private static final Logger log = LoggerFactory.getLogger(AIGateway.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern SECRET_RULE = Pattern.compile("(?i).*(secret|credential|password|private-key|token|api-key).*");

	/** Below this many tokens a truncated excerpt is not worth sending. */
	private static final int MIN_TRUNCATED_TOKENS = 120;

	private final AIProperties properties;

	private final AIProviderRouter router;

	private final PromptScrubber scrubber;

	private final TokenEstimator estimator;

	private final StructuredOutputValidator validator;

	private final AIResponseCache cache;

	private final AIUsageService usage;

	private final AnalysisFindingRepository findings;

	private final Clock clock;

	private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

	public AIGateway(AIProperties properties, AIProviderRouter router, GenericSecretScanAnalyzer secretScanner,
			AIResponseCache cache, AIUsageService usage, AnalysisFindingRepository findings, Clock clock) {
		this.properties = properties;
		this.router = router;
		this.scrubber = new PromptScrubber(secretScanner);
		this.estimator = new TokenEstimator(properties.tokenEstimate().charsPerToken());
		this.validator = new StructuredOutputValidator();
		this.cache = cache;
		this.usage = usage;
		this.findings = findings;
		this.clock = clock;
	}

	public GatewayResult execute(GatewayRequest request) {
		PromptTemplate template = request.template();
		String promptVersion = template.promptVersion();
		String feature = template.feature();

		// 1. enabled
		if (!properties.enabled()) {
			return GatewayResult.notRun(AIStatus.UNAVAILABLE, promptVersion,
					"AI insights are unavailable: AI is disabled (ai.enabled=false).");
		}
		// 2. permitted, healthy provider
		RoutingDecision decision = router
			.route(new RoutingContext(feature, request.preferredProvider(), request.hostedPermitted()));
		if (!decision.routed()) {
			return GatewayResult.notRun(AIStatus.UNAVAILABLE, promptVersion, decision.unavailableReason());
		}
		AIModelService service = decision.service();
		String model = decision.model() != null ? decision.model() : service.getCapabilities().modelName();

		// 3. secret scrub (before anything is hashed, cached or sent)
		List<EvidenceBlock> numbered = new ArrayList<>();
		for (EvidenceBlock block : request.evidence()) {
			numbered.add(block.withId("E" + (numbered.size() + 1)));
		}
		PromptScrubber.Scrubbed scrubbed = scrubber.scrub(numbered,
				secretRegions(request.snapshotId(), request.runId()));

		// 4. bounded prompt
		int maxOutput = Math.min(template.maxOutputTokens(), properties.limits().maxOutputTokens());
		int inputBudget = properties.limits().maxInputTokens();
		Integer context = service.getCapabilities().maxContextTokens();
		if (context != null && context - maxOutput < inputBudget) {
			inputBudget = Math.max(0, context - maxOutput);
		}
		String system = PromptAssembler.system(template);
		List<EvidenceBlock> included = fit(template, system, scrubbed.blocks(), inputBudget);
		if (included == null) {
			return GatewayResult.notRun(AIStatus.UNAVAILABLE, promptVersion,
					"AI insights are unavailable: the instructions alone exceed the input token budget ("
							+ inputBudget + ").");
		}
		String user = PromptAssembler.user(template, included);
		int estimatedInput = estimator.estimate(system) + estimator.estimate(user);
		String schemaJson = template.schema() == null ? null : JSON.writeValueAsString(template.schema());
		Set<String> ids = new LinkedHashSet<>();
		included.forEach(block -> ids.add(block.id()));

		// 5. cache
		String key = AIResponseCache.key(service.getProviderId(), model, promptVersion, request.commitSha(), system, user,
				schemaJson);
		Optional<AICacheEntry> hit = cache.lookup(key);
		if (hit.isPresent()) {
			AICacheEntry entry = hit.get();
			record(request, service.getProviderId(), entry.getAnsweredModel(), AIStatus.OK, true, null, null,
					estimatedInput, null, 0L, null);
			return new GatewayResult(AIStatus.OK, entry.getContent(), entry.getStructured(), service.getProviderId(),
					entry.getAnsweredModel(), promptVersion, true, decision.fallback(), null, included,
					scrubbed.withheldLines(), List.of(), entry.getCreatedAt());
		}

		// 6. call budget
		if (!reserve(request.runId())) {
			return new GatewayResult(AIStatus.SKIPPED, null, null, service.getProviderId(), model, promptVersion, false,
					decision.fallback(), "AI call budget reached: at most " + properties.limits().maxCallsPerAnalysis()
							+ " provider calls per analysis (ai.limits.max-calls-per-analysis). Cached answers are still served.",
					included, scrubbed.withheldLines(), List.of(), null);
		}
		try {
			// 7. provider call (timeout and retry limit inside the adapter)
			AIRequest aiRequest = new AIRequest(system, user, promptVersion, maxOutput, template.temperature(),
					template.schema(), decision.model(), request.repositoryId(), request.snapshotId(), feature);
			long started = System.nanoTime();
			AIResponse response;
			try {
				response = service.generate(aiRequest);
			}
			catch (RuntimeException ex) {
				log.warn("Provider {} failed unexpectedly: {}", service.getProviderId(), ex.getClass().getSimpleName());
				response = AIResponse.failure(service.getProviderId(), model, AIStatus.ERROR,
						"The provider call failed unexpectedly (" + ex.getClass().getSimpleName() + ").");
			}
			long latency = (System.nanoTime() - started) / 1_000_000;
			if (response == null) {
				response = AIResponse.failure(service.getProviderId(), model, AIStatus.ERROR, "The provider returned nothing.");
			}
			String answeredModel = response.model() != null ? response.model() : model;
			if (response.status() == AIStatus.RATE_LIMITED) {
				router.availability().markRateLimited(service.getProviderId(), response.retryAfter());
			}
			else if (!response.isOk()) {
				router.availability().invalidate(service.getProviderId());
			}

			// 8. validation
			List<String> warnings = new ArrayList<>();
			Object structured = null;
			String content = response.content();
			if (response.isOk()) {
				if (template.schema() != null) {
					StructuredOutputValidator.Validation validation = validator.validate(content, template.schema());
					if (!validation.valid()) {
						response = response.withStatus(AIStatus.INVALID_OUTPUT,
								"The model's answer was rejected: " + validation.error() + ".");
					}
					else {
						JsonNode cleaned = clean(validation.value(), ids, warnings);
						structured = JSON.treeToValue(cleaned, Object.class);
						content = JSON.writeValueAsString(cleaned);
					}
				}
				else {
					content = SecretRedactor.redact(content);
				}
			}

			// 9. usage record
			Integer estimatedOutput = content == null ? null : estimator.estimate(content);
			record(request, service.getProviderId(), answeredModel, response.status(), false, response.inputTokens(),
					response.outputTokens(), estimatedInput, estimatedOutput, latency, response.detail());

			if (!response.isOk()) {
				return new GatewayResult(response.status(), null, null, service.getProviderId(), answeredModel,
						promptVersion, false, decision.fallback(), response.detail(), included, scrubbed.withheldLines(),
						List.of(), null);
			}
			// 10. cache store
			cache.store(key, service.getProviderId(), model, promptVersion, feature, request.commitSha(), content,
					structured, answeredModel);
			// 11. AI-generated result
			return new GatewayResult(AIStatus.OK, content, structured, service.getProviderId(), answeredModel,
					promptVersion, false, decision.fallback(), null, included, scrubbed.withheldLines(), warnings,
					clock.instant());
		}
		finally {
			release(request.runId());
		}
	}

	/** Lines of files where the secret scanner (or any secret-like security rule) reported something. */
	List<SecretRegion> secretRegions(UUID snapshotId, UUID runId) {
		if (snapshotId == null || runId == null) {
			return List.of();
		}
		List<SecretRegion> regions = new ArrayList<>();
		for (AnalysisFinding finding : findings.findAllBySnapshotIdAndRunIdAndCategory(snapshotId, runId,
				AnalysisCategory.SECURITY)) {
			boolean secret = GenericSecretScanAnalyzer.ID.equals(finding.getAnalyzerId())
					|| SECRET_RULE.matcher(finding.getRuleId()).matches();
			if (secret && finding.getFilePath() != null && finding.getStartLine() != null) {
				int end = finding.getEndLine() == null ? finding.getStartLine() : finding.getEndLine();
				regions.add(new SecretRegion(finding.getFilePath(), finding.getStartLine(), end));
			}
		}
		return regions;
	}

	/**
	 * Keeps blocks in priority order while they fit; the first block that does not fit is cut to the
	 * remaining budget (whole lines) if enough is left, and later blocks are tried in turn. Null when the
	 * instructions alone exceed the budget.
	 */
	List<EvidenceBlock> fit(PromptTemplate template, String system, List<EvidenceBlock> blocks, int budget) {
		int used = estimator.estimate(system) + estimator.estimate(PromptAssembler.user(template, List.of()));
		if (used > budget) {
			return null;
		}
		List<EvidenceBlock> included = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			int cost = estimator.estimate(PromptAssembler.render(block)) + 1;
			if (used + cost <= budget) {
				included.add(block);
				used += cost;
				continue;
			}
			int remaining = budget - used;
			if (remaining < MIN_TRUNCATED_TOKENS) {
				continue;
			}
			EvidenceBlock cut = truncate(block, remaining);
			if (cut != null) {
				included.add(cut);
				used += estimator.estimate(PromptAssembler.render(cut)) + 1;
			}
		}
		return included;
	}

	private EvidenceBlock truncate(EvidenceBlock block, int tokens) {
		String[] lines = block.text().split("\n", -1);
		// header and the truncation note cost a little; keep whole lines that fit
		int allowedChars = estimator.charsFor(tokens) - PromptAssembler.render(block.withText("", block.startLine(), true)).length() - 40;
		StringBuilder kept = new StringBuilder();
		int count = 0;
		for (String line : lines) {
			if (kept.length() + line.length() + 1 > allowedChars) {
				break;
			}
			if (count > 0) {
				kept.append('\n');
			}
			kept.append(line);
			count++;
		}
		if (count == 0) {
			return null;
		}
		kept.append("\n... (truncated to fit the input token budget)");
		Integer end = block.startLine() == null ? null : block.startLine() + count - 1;
		return block.withText(kept.toString(), end, true);
	}

	/** Drops evidence references that name no block that was sent, and redacts every string. */
	private static JsonNode clean(JsonNode node, Set<String> ids, List<String> warnings) {
		if (node instanceof ObjectNode object) {
			for (String name : List.copyOf(object.propertyNames())) {
				JsonNode child = object.get(name);
				if (name.endsWith("Refs") && child instanceof ArrayNode refs) {
					ArrayNode kept = JSON.createArrayNode();
					for (JsonNode ref : refs) {
						String value = ref.isString() ? ref.asString() : ref.toString();
						if (ids.contains(value)) {
							kept.add(value);
						}
						else {
							warnings.add("The answer cited unknown evidence '" + truncate(value) + "'; the reference was dropped.");
						}
					}
					object.set(name, kept);
				}
				else {
					object.set(name, clean(child, ids, warnings));
				}
			}
			return object;
		}
		if (node instanceof ArrayNode array) {
			for (int i = 0; i < array.size(); i++) {
				array.set(i, clean(array.get(i), ids, warnings));
			}
			return array;
		}
		if (node != null && node.isString()) {
			return StringNode.valueOf(SecretRedactor.redact(node.asString()));
		}
		return node;
	}

	private static String truncate(String value) {
		return value.length() <= 40 ? value : value.substring(0, 40) + "...";
	}

	private void record(GatewayRequest request, String provider, String model, AIStatus status, boolean cacheHit,
			Integer inputTokens, Integer outputTokens, int estimatedInput, Integer estimatedOutput, Long latency,
			String detail) {
		try {
			usage.record(new AIUsageService.Call(provider, model, request.template().feature(),
					request.template().promptVersion(), request.repositoryId(), request.snapshotId(), request.runId(), status,
					cacheHit, inputTokens, outputTokens, estimatedInput, estimatedOutput, latency, detail));
		}
		catch (RuntimeException ex) {
			// usage accounting must not turn a good answer into an error
			log.warn("Could not record AI usage: {}", ex.getClass().getSimpleName());
		}
	}

	private boolean reserve(UUID runId) {
		AtomicInteger counter = inFlight.computeIfAbsent(runId == null ? new UUID(0, 0) : runId, id -> new AtomicInteger());
		synchronized (counter) {
			long used = usage.callsForRun(runId) + counter.get();
			if (used >= properties.limits().maxCallsPerAnalysis()) {
				return false;
			}
			counter.incrementAndGet();
			return true;
		}
	}

	private void release(UUID runId) {
		AtomicInteger counter = inFlight.get(runId == null ? new UUID(0, 0) : runId);
		if (counter != null) {
			synchronized (counter) {
				counter.decrementAndGet();
			}
		}
	}

}
