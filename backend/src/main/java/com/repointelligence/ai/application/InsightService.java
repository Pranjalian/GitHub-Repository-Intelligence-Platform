package com.repointelligence.ai.application;

import com.repointelligence.ai.api.InsightDTO;
import com.repointelligence.ai.api.InsightRequestDTO;
import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.prompt.PromptTemplate;
import com.repointelligence.ai.prompt.PromptTemplateRegistry;
import com.repointelligence.ai.provider.AIProperties;
import com.repointelligence.ai.provider.AIProviderRouter;
import com.repointelligence.ai.provider.AIStatus;
import com.repointelligence.ai.provider.RoutingContext;
import com.repointelligence.ai.provider.RoutingDecision;
import com.repointelligence.report.application.SourceUrls;
import com.repointelligence.repository.application.SnapshotSourceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * AI insights for one snapshot: explain a class, method or finding, suggest refactorings, summarise a
 * module, summarise technical debt. The target must belong to the latest completed run of the snapshot
 * (404 otherwise). With AI disabled or no permitted provider the answer is {@code UNAVAILABLE} with a
 * reason, and nothing is collected or downloaded. Otherwise evidence is collected (parser facts and
 * findings, plus bounded source excerpts) and handed to the {@link AIGateway}, which applies every
 * guardrail. Repository code is only read, never executed.
 */
@Service
public class InsightService {

	private final InsightEvidenceBuilder builder;

	private final SnapshotSourceService sources;

	private final PromptTemplateRegistry templates;

	private final AIGateway gateway;

	private final AIProviderRouter router;

	private final AIProperties properties;

	public InsightService(InsightEvidenceBuilder builder, SnapshotSourceService sources,
			PromptTemplateRegistry templates, AIGateway gateway, AIProviderRouter router, AIProperties properties) {
		this.builder = builder;
		this.sources = sources;
		this.templates = templates;
		this.gateway = gateway;
		this.router = router;
		this.properties = properties;
	}

	public InsightDTO insight(UUID repositoryId, UUID snapshotId, InsightRequestDTO request) {
		InsightEvidenceBuilder.Scope scope = builder.scope(repositoryId, snapshotId);
		PromptTemplate template = templates.forFeature(request.type().feature());
		boolean hostedPermitted = "public".equalsIgnoreCase(scope.visibility())
				|| properties.allowHostedForPrivateRepositories();

		// the target is validated first, so a wrong id is a 404/400 whether or not AI is enabled
		InsightEvidenceBuilder.Plan plan = builder.plan(request.type(), request.targetId(), scope,
				properties.source().maxLines());

		// nothing is collected or downloaded unless a permitted provider is available
		RoutingDecision decision = router
			.route(new RoutingContext(template.feature(), request.provider(), hostedPermitted));
		if (!decision.routed()) {
			return unavailable(request, plan.targetLabel(), template, decision.unavailableReason(), scope.commitSha());
		}

		List<String> limitations = new ArrayList<>(plan.limitations());
		List<EvidenceBlock> evidence = new ArrayList<>();
		evidence.add(plan.target());
		for (InsightEvidenceBuilder.SourceNeed need : plan.sources()) {
			SnapshotSourceService.Excerpt excerpt = sources.read(snapshotId, need.filePath(), need.startLine(),
					need.endLine(), properties.source().allowDownload());
			if (excerpt.available()) {
				evidence.add(new EvidenceBlock(null, need.kind(), need.filePath(), excerpt.startLine(), excerpt.endLine(),
						excerpt.text(), SourceUrls.blobUrl(scope.owner(), scope.repo(), scope.commitSha(), need.filePath(),
								excerpt.startLine(), excerpt.endLine()),
						false));
			}
			else {
				limitations.add("Source of " + need.filePath() + " was not sent: " + excerpt.unavailableReason() + ".");
			}
		}
		if (plan.findingsFirst()) {
			evidence.addAll(plan.findings());
			evidence.addAll(plan.declarations());
		}
		else {
			evidence.addAll(plan.declarations());
			evidence.addAll(plan.findings());
		}

		GatewayResult result = gateway.execute(new GatewayRequest(template, evidence, repositoryId, snapshotId,
				scope.runId(), scope.commitSha(), request.provider(), hostedPermitted));
		int requested = evidence.size();
		if (result.status() == AIStatus.OK && result.evidence().size() < requested) {
			limitations.add((requested - result.evidence().size())
					+ " evidence block(s) did not fit the input token budget and were not sent.");
		}
		if (result.withheldLines() > 0) {
			limitations.add(result.withheldLines() + " line(s) were withheld because a possible secret was detected.");
		}
		return new InsightDTO(request.type(), request.targetId(), plan.targetLabel(), result.status(), result.aiGenerated(),
				result.aiGenerated() ? explanation(result) : null, result.aiGenerated() ? result.structured() : null,
				result.provider(), result.model(), result.promptVersion(), result.fromCache(), result.fallback(),
				result.detail(), result.evidence().stream().map(InsightService::toDto).toList(), result.withheldLines(),
				result.warnings(), limitations, scope.commitSha(), result.generatedAt(), setupGuide());
	}

	private InsightDTO unavailable(InsightRequestDTO request, String label, PromptTemplate template, String reason,
			String sha) {
		return new InsightDTO(request.type(), request.targetId(), label, AIStatus.UNAVAILABLE, false, null, null, null, null,
				template.promptVersion(), false, false, reason, List.of(), 0, List.of(), List.of(), sha, null, setupGuide());
	}

	private String setupGuide() {
		String url = properties.setupGuideUrl();
		return url == null || url.isBlank() ? null : url;
	}

	/** The structured answer's summary (Markdown), else the raw validated content. */
	private static String explanation(GatewayResult result) {
		if (result.structured() instanceof Map<?, ?> map && map.get("summary") instanceof String summary) {
			return summary;
		}
		return result.content();
	}

	private static InsightDTO.EvidenceDTO toDto(EvidenceBlock block) {
		return new InsightDTO.EvidenceDTO(block.id(), block.kind(), block.filePath(), block.startLine(), block.endLine(),
				block.sourceUrl(), block.truncated(), block.text());
	}

}
