package com.repointelligence.impact.api;

import com.repointelligence.common.model.Confidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The impact of changing one symbol, commit-scoped. Everything except {@code aiSummary} comes from the stored
 * AST graph and is identical with AI disabled. Components form a tree rooted at the target through
 * {@code viaId}.
 */
public record ImpactAnalysisDTO(UUID repositoryId, UUID snapshotId, UUID runId, String commitSha, String status,
		String reason, Target target, int depth, List<Component> components, List<Endpoint> endpoints,
		List<RelatedData> relatedData, List<Module> modules, List<TestLink> tests, List<RegressionArea> regressionAreas,
		Risk risk, List<String> limitations, boolean truncated, int totalDependents, Diagram diagram,
		AiSummary aiSummary) {

	public record Target(UUID id, String kind, String name, String qualifiedName, String layer, String filePath,
			Integer startLine, Integer endLine, String sourceUrl, String modulePath) {
	}

	public record Component(UUID id, String kind, String name, String qualifiedName, String layer, int depth, UUID viaId,
			List<String> relationships, Confidence confidence, String reason, String filePath, Integer line,
			String sourceUrl, String modulePath) {
	}

	public record Endpoint(String httpMethod, String route, boolean routeResolved, String controller, String handler,
			Confidence confidence, String filePath, Integer line, String sourceUrl, UUID viaId) {
	}

	public record RelatedData(String kind, UUID id, String qualifiedName, String relationship, String via,
			Confidence confidence, String filePath, Integer line, String sourceUrl) {
	}

	public record Module(String modulePath, String reason, int depth, int affectedSymbols, List<String> evidencePaths,
			Confidence confidence) {
	}

	public record TestLink(String testSymbol, String filePath, List<String> methods, String relation,
			Confidence confidence, Integer line, String sourceUrl, String via) {
	}

	/** Something to re-test or check by hand, with the reason. */
	public record RegressionArea(String kind, String area, String reason, Confidence confidence) {
	}

	/** A heuristic, never a probability: the score is the sum of the visible factor points. */
	public record Risk(String label, int score, int maxScore, String level, List<RiskFactor> factors, String note) {
	}

	public record RiskFactor(String id, String label, String value, int points, int maxPoints, String explanation) {
	}

	/** Mermaid flowchart source of the dependency chain; null when there is nothing to draw. */
	public record Diagram(String chain, String note) {
	}

	/** The optional model-written summary; {@code aiGenerated} is true only for {@code status == OK}. */
	public record AiSummary(String status, boolean aiGenerated, String summary, List<String> keyPoints,
			List<String> regressionFocus, String provider, String model, String promptVersion, boolean fromCache,
			String detail, List<String> warnings, Instant generatedAt) {
	}

}
