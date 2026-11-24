package com.repointelligence.impact.application;

import com.repointelligence.ai.application.AIGateway;
import com.repointelligence.ai.application.GatewayRequest;
import com.repointelligence.ai.application.GatewayResult;
import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.prompt.PromptTemplate;
import com.repointelligence.ai.prompt.PromptTemplateRegistry;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderRouter;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.RoutingContext;
import com.repointelligence.ai.provider.RoutingDecision;
import com.repointelligence.impact.ImpactProperties;
import com.repointelligence.impact.api.ImpactAnalysisDTO;
import com.repointelligence.impact.api.ImpactAnalysisDTO.AiSummary;
import com.repointelligence.impact.api.SymbolPickerDTO.PickerSymbol;
import com.repointelligence.impact.api.TargetSuggestionDTO;
import com.repointelligence.impact.api.TargetSuggestionDTO.Suggestion;
import com.repointelligence.repository.application.SnapshotSourceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The two optional AI features of impact analysis, both through the {@link AIGateway} (so every MVP 3
 * guardrail applies: secret scrub, token and call budgets, cache, schema validation, usage record):
 * a summary of an impact result, and suggestions that map a free-text change description to symbols that
 * exist in the snapshot. Neither changes the graph result, and nothing is collected or sent unless a
 * permitted provider is available.
 */
@Service
public class ImpactAiService {

	static final String SUMMARY_FEATURE = "summarize-impact";

	static final String SUGGEST_FEATURE = "suggest-impact-targets";

	private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}");

	private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "from", "into", "that", "this", "when",
			"change", "changes", "changed", "add", "adding", "update", "make", "use", "using", "should", "need", "needs",
			"new", "method", "class", "function", "code", "all", "any", "not", "are", "was", "will");

	private final AIGateway gateway;

	private final AIProviderRouter router;

	private final PromptTemplateRegistry templates;

	private final AIProperties aiProperties;

	private final ImpactProperties properties;

	private final SnapshotSourceService sources;

	private final ImpactSymbolPicker picker;

	public ImpactAiService(AIGateway gateway, AIProviderRouter router, PromptTemplateRegistry templates,
			AIProperties aiProperties, ImpactProperties properties, SnapshotSourceService sources, ImpactSymbolPicker picker) {
		this.gateway = gateway;
		this.router = router;
		this.templates = templates;
		this.aiProperties = aiProperties;
		this.properties = properties;
		this.sources = sources;
		this.picker = picker;
	}

	private boolean hostedPermitted(ImpactScope scope) {
		return "public".equalsIgnoreCase(scope.visibility())
				|| aiProperties.allowHostedForPrivateRepositories();
	}

	// ── summary ──────────────────────────────────────────────────────────────────────────────────

	public AiSummary summarize(ImpactScope scope, ImpactAnalysisDTO result, String changeDescription, String provider) {
		PromptTemplate template = templates.forFeature(SUMMARY_FEATURE);
		boolean hosted = hostedPermitted(scope);
		RoutingDecision decision = router.route(new RoutingContext(SUMMARY_FEATURE, provider, hosted));
		if (!decision.routed()) {
			return new AiSummary("UNAVAILABLE", false, null, List.of(), List.of(), null, null, template.promptVersion(), false,
					decision.unavailableReason(), List.of(), null);
		}
		UUID snapshotId = scope.snapshotId();
		String sha = scope.commitSha();
		ImpactAnalysisDTO.Target target = result.target();

		List<EvidenceBlock> evidence = new ArrayList<>();
		evidence.add(new EvidenceBlock(null, "TARGET", target.filePath(), target.startLine(), target.endLine(),
				"Target (" + target.kind() + "): " + target.qualifiedName() + "\nLayer: " + target.layer() + "\nModule: "
						+ target.modulePath(),
				target.sourceUrl(), false));
		if (changeDescription != null && !changeDescription.isBlank()) {
			evidence.add(new EvidenceBlock(null, "CHANGE_DESCRIPTION", null, null, null, changeDescription.strip(), null, false));
		}
		evidence.add(new EvidenceBlock(null, "IMPACT", null, null, null, impactText(result), null, false));
		evidence.add(new EvidenceBlock(null, "RISK", null, null, null, riskText(result), null, false));
		evidence.add(new EvidenceBlock(null, "LIMITATIONS", null, null, null,
				String.join("\n", result.limitations().stream().map(l -> "- " + l).toList()), null, false));
		if (target.filePath() != null && target.startLine() != null) {
			int end = Math.min(target.endLine() == null ? target.startLine() + 60 : target.endLine(),
					target.startLine() + Math.min(aiProperties.source().maxLines(), 120) - 1);
			SnapshotSourceService.Excerpt excerpt = sources.read(snapshotId, target.filePath(), target.startLine(), end,
					aiProperties.source().allowDownload());
			if (excerpt.available()) {
				evidence.add(new EvidenceBlock(null, "SOURCE", target.filePath(), excerpt.startLine(), excerpt.endLine(),
						excerpt.text(), scope.url(target.filePath(), excerpt.startLine(), excerpt.endLine()), false));
			}
		}
		GatewayResult gateway = this.gateway.execute(new GatewayRequest(template, evidence, scope.repositoryId(), snapshotId,
				scope.runId(), sha, provider, hosted));
		List<String> warnings = new ArrayList<>(gateway.warnings());
		if (gateway.withheldLines() > 0) {
			warnings.add(gateway.withheldLines() + " line(s) were withheld from the prompt because a possible secret was detected.");
		}
		if (gateway.status() != AIStatus.OK) {
			return new AiSummary(gateway.status().name(), false, null, List.of(), List.of(), gateway.provider(), gateway.model(),
					gateway.promptVersion(), gateway.fromCache(), gateway.detail(), warnings, null);
		}
		Map<?, ?> structured = gateway.structured() instanceof Map<?, ?> map ? map : Map.of();
		return new AiSummary("OK", true, text(structured.get("summary")), strings(structured.get("keyPoints")),
				strings(structured.get("regressionFocus")), gateway.provider(), gateway.model(), gateway.promptVersion(),
				gateway.fromCache(), gateway.detail(), warnings, gateway.generatedAt());
	}

	private static String impactText(ImpactAnalysisDTO result) {
		StringBuilder text = new StringBuilder();
		text.append("Affected components (").append(result.totalDependents()).append(result.truncated() ? ", capped" : "")
			.append("):\n");
		result.components()
			.stream()
			.limit(40)
			.forEach(c -> text.append("- depth ").append(c.depth()).append(' ').append(c.confidence()).append(' ')
				.append(c.qualifiedName()).append(c.layer() == null ? "" : " [" + c.layer() + "]").append(" via ")
				.append(String.join("+", c.relationships())).append('\n'));
		text.append("Endpoints:\n");
		result.endpoints().forEach(e -> text.append("- ").append(e.httpMethod()).append(' ').append(e.route()).append(' ')
			.append(e.confidence()).append('\n'));
		text.append("Related entities and repositories:\n");
		result.relatedData().stream().limit(20).forEach(r -> text.append("- ").append(r.kind()).append(' ')
			.append(r.qualifiedName()).append(" (").append(r.relationship()).append(")\n"));
		text.append("Modules:\n");
		result.modules().forEach(m -> text.append("- ").append(m.modulePath()).append(' ').append(m.reason()).append('\n'));
		text.append("Tests:\n");
		result.tests().stream().limit(30).forEach(t -> text.append("- ").append(t.testSymbol()).append(' ').append(t.relation())
			.append(' ').append(t.confidence()).append('\n'));
		return text.toString();
	}

	private static String riskText(ImpactAnalysisDTO result) {
		StringBuilder text = new StringBuilder(result.risk().label()).append("\nScore: ").append(result.risk().score())
			.append('/').append(result.risk().maxScore()).append(" (").append(result.risk().level()).append(")\n");
		result.risk().factors().forEach(f -> text.append("- ").append(f.label()).append(": ").append(f.value()).append(" -> ")
			.append(f.points()).append('/').append(f.maxPoints()).append('\n'));
		return text.toString();
	}

	// ── target suggestions ───────────────────────────────────────────────────────────────────────

	public TargetSuggestionDTO suggest(ImpactScope scope, String changeDescription, String provider) {
		PromptTemplate template = templates.forFeature(SUGGEST_FEATURE);
		boolean hosted = hostedPermitted(scope);
		RoutingDecision decision = router.route(new RoutingContext(SUGGEST_FEATURE, provider, hosted));
		if (!decision.routed()) {
			return new TargetSuggestionDTO("UNAVAILABLE", false, decision.unavailableReason(), null, null,
					template.promptVersion(), List.of(), List.of());
		}
		UUID snapshotId = scope.snapshotId();
		UUID runId = scope.runId();
		List<PickerSymbol> candidates = candidates(snapshotId, runId, changeDescription);
		if (candidates.isEmpty()) {
			return new TargetSuggestionDTO("OK", false,
					"No symbol of this snapshot matches a word of the description; use the symbol picker.", null, null,
					template.promptVersion(), List.of(), List.of());
		}
		String sha = scope.commitSha();
		List<EvidenceBlock> evidence = new ArrayList<>();
		evidence.add(new EvidenceBlock(null, "CHANGE_DESCRIPTION", null, null, null, changeDescription.strip(), null, false));
		Map<String, PickerSymbol> byText = new LinkedHashMap<>();
		for (PickerSymbol candidate : candidates) {
			String text = "SYMBOL " + candidate.kind() + " " + candidate.qualifiedName()
					+ (candidate.layer() == null ? "" : "\nlayer: " + candidate.layer()) + "\nfile: " + candidate.filePath();
			byText.put(candidate.qualifiedName(), candidate);
			evidence.add(new EvidenceBlock(null, "CANDIDATE", candidate.filePath(), candidate.line(), candidate.line(), text,
					scope.url(candidate.filePath(), candidate.line(), candidate.line()), false));
		}
		GatewayResult result = gateway.execute(new GatewayRequest(template, evidence, scope.repositoryId(), snapshotId, runId, sha,
				provider, hosted));
		if (result.status() != AIStatus.OK) {
			return new TargetSuggestionDTO(result.status().name(), false, result.detail(), result.provider(), result.model(),
					result.promptVersion(), List.of(), result.warnings());
		}
		Map<String, PickerSymbol> sent = new LinkedHashMap<>();
		for (EvidenceBlock block : result.evidence()) {
			if ("CANDIDATE".equals(block.kind()) && block.text() != null) {
				for (Map.Entry<String, PickerSymbol> entry : byText.entrySet()) {
					if (block.text().contains(" " + entry.getKey() + "\n") || block.text().endsWith(" " + entry.getKey())) {
						sent.put(block.id(), entry.getValue());
					}
				}
			}
		}
		List<Suggestion> suggestions = new ArrayList<>();
		List<String> warnings = new ArrayList<>(result.warnings());
		Object raw = result.structured() instanceof Map<?, ?> map ? map.get("suggestions") : null;
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (!(item instanceof Map<?, ?> entry)) {
					continue;
				}
				PickerSymbol symbol = sent.get(String.valueOf(entry.get("ref")));
				if (symbol == null) {
					warnings.add("A suggestion named a symbol that was not among the candidates; it was dropped.");
					continue;
				}
				if (suggestions.stream().noneMatch(s -> s.symbolId().equals(symbol.id()))) {
					suggestions.add(new Suggestion(symbol.id(), symbol.kind(), symbol.name(), symbol.qualifiedName(),
							symbol.filePath(), symbol.line(), text(entry.get("reason"))));
				}
			}
		}
		return new TargetSuggestionDTO("OK", true, null, result.provider(), result.model(), result.promptVersion(), suggestions,
				warnings);
	}

	/** Symbols whose names contain words of the description, best matches first: deterministic, no model. */
	List<PickerSymbol> candidates(UUID snapshotId, UUID runId, String description) {
		Map<UUID, int[]> score = new LinkedHashMap<>();
		Map<UUID, PickerSymbol> symbols = new LinkedHashMap<>();
		Matcher matcher = WORD.matcher(description);
		Set<String> seen = new java.util.HashSet<>();
		while (matcher.find() && seen.size() < 8) {
			String word = matcher.group().toLowerCase(Locale.ROOT);
			if (STOP_WORDS.contains(word) || !seen.add(word)) {
				continue;
			}
			for (PickerSymbol symbol : picker.find(snapshotId, runId, word, 15)) {
				symbols.putIfAbsent(symbol.id(), symbol);
				score.computeIfAbsent(symbol.id(), k -> new int[1])[0]++;
			}
		}
		return symbols.values()
			.stream()
			.sorted(java.util.Comparator.comparingInt((PickerSymbol s) -> -score.get(s.id())[0]).thenComparing(PickerSymbol::qualifiedName))
			.limit(properties.suggestionCandidates())
			.toList();
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static List<String> strings(Object value) {
		return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
	}

}
