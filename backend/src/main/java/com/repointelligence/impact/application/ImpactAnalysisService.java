package com.repointelligence.impact.application;

import com.repointelligence.analysis.spi.ImpactAnalyzer;
import com.repointelligence.analysis.spi.ImpactGraph;
import com.repointelligence.analysis.spi.ImpactGraph.Node;
import com.repointelligence.analysis.spi.ImpactQuery;
import com.repointelligence.analysis.spi.ImpactResult;
import com.repointelligence.common.exception.BadRequestException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.impact.ImpactProperties;
import com.repointelligence.impact.api.ImpactAnalysisDTO;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Component;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Diagram;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Endpoint;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Module;
import com.repointelligence.impact.api.ImpactAnalysisDTO.RegressionArea;
import com.repointelligence.impact.api.ImpactAnalysisDTO.RelatedData;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Risk;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Target;
import com.repointelligence.impact.api.ImpactAnalysisDTO.TestLink;
import com.repointelligence.impact.api.ImpactAnalysisRequestDTO;
import com.repointelligence.impact.api.SymbolPickerDTO;
import com.repointelligence.impact.api.TargetSuggestionDTO;
import com.repointelligence.impact.graph.ImpactGraphFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Coordinates an impact analysis: resolves the snapshot and its latest completed run, validates the target
 * (a symbol picked from {@code code_symbol}, 404 for anything else), hands the stored graph to the
 * {@link ImpactAnalyzer} of the target's language, and adds the heuristic risk score, regression areas,
 * source links and the Mermaid chain. The optional AI summary is added last and never alters the result: with
 * AI disabled everything else is identical.
 */
@Service
public class ImpactAnalysisService {

	private final ImpactScopeResolver scopes;

	private final ImpactGraphFactory graphs;

	private final List<ImpactAnalyzer> analyzers;

	private final RiskScorer risk;

	private final ImpactContextQueries context;

	private final ImpactDiagramService diagrams;

	private final ImpactSymbolPicker picker;

	private final ImpactAiService ai;

	private final ImpactProperties properties;

	public ImpactAnalysisService(ImpactScopeResolver scopes, ImpactGraphFactory graphs, List<ImpactAnalyzer> analyzers,
			RiskScorer risk, ImpactContextQueries context, ImpactDiagramService diagrams, ImpactSymbolPicker picker,
			ImpactAiService ai, ImpactProperties properties) {
		this.scopes = scopes;
		this.graphs = graphs;
		this.analyzers = analyzers.stream().sorted(java.util.Comparator.comparing(a -> a.descriptor().id())).toList();
		this.risk = risk;
		this.context = context;
		this.diagrams = diagrams;
		this.picker = picker;
		this.ai = ai;
		this.properties = properties;
	}

	public ImpactAnalysisDTO analyze(UUID repositoryId, UUID snapshotId, ImpactAnalysisRequestDTO request) {
		ImpactScope scope = scopes.resolve(repositoryId, snapshotId);
		int depth = request.depth() == null ? properties.defaultDepth() : request.depth();
		if (depth < 1 || depth > properties.maxDepth()) {
			throw new BadRequestException("depth must be between 1 and " + properties.maxDepth() + ".");
		}
		ImpactGraph graph = graphs.open(snapshotId, scope.runId());
		Node targetNode = graph.node(request.targetSymbolId())
			.orElseThrow(() -> new ResourceNotFoundException("Symbol not found in the latest completed analysis of this snapshot."));
		if (!targetNode.isType() && !targetNode.isCallable()) {
			throw new BadRequestException("Impact analysis works on classes, interfaces, methods and constructors, not on "
					+ targetNode.type().toLowerCase(java.util.Locale.ROOT) + " symbols.");
		}
		ImpactAnalyzer analyzer = analyzers.stream().filter(a -> a.supports(targetNode)).findFirst().orElse(null);
		if (analyzer == null) {
			return unsupported(scope, targetNode, depth);
		}
		ImpactResult result = analyzer.analyze(new ImpactQuery(targetNode.id(), depth, properties.maxComponents()), graph);

		String targetName = name(targetNode);
		Target target = new Target(targetNode.id(), result.targetKind(), targetName, qualified(targetNode), result.targetLayer(),
				targetNode.filePath(), targetNode.startLine(), targetNode.endLine(),
				scope.url(targetNode.filePath(), targetNode.startLine(), targetNode.endLine()), targetNode.modulePath());

		Set<String> files = new LinkedHashSet<>();
		files.add(targetNode.filePath());
		result.components().forEach(c -> files.add(c.filePath()));
		files.remove(null);
		int[] findings = context.securityFindings(snapshotId, scope.runId(), files);
		String targetType = targetNode.isType() ? targetNode.fqn() : targetNode.ownerName();
		Double coverage = context.lineCoverage(snapshotId, scope.runId(), targetType);
		ImpactGraph.GraphStats stats = graph.stats();
		Risk riskScore = risk.score(result, new RiskScorer.Inputs(findings[0], findings[1], coverage,
				stats.testsAnalysed() && stats.testReferencesAvailable()));

		List<Component> components = result.components()
			.stream()
			.map(c -> new Component(c.id(), c.kind(), c.name(), c.qualifiedName(), c.layer(), c.depth(), c.viaId(),
					c.relationships(), c.confidence(), c.reason(), c.filePath(), c.line(), scope.url(c.filePath(), c.line(), c.endLine()),
					c.modulePath()))
			.toList();
		List<Endpoint> endpoints = result.endpoints()
			.stream()
			.map(e -> new Endpoint(e.httpMethod(), e.route(), e.routeResolved(), e.controller(), e.handler(), e.confidence(),
					e.filePath(), e.line(), scope.url(e.filePath(), e.line(), null), e.viaId()))
			.toList();
		List<RelatedData> related = result.relatedData()
			.stream()
			.map(r -> new RelatedData(r.kind(), r.id(), r.qualifiedName(), r.relationship(), r.viaName(), r.confidence(),
					r.filePath(), r.line(), scope.url(r.filePath(), r.line(), null)))
			.toList();
		List<Module> modules = result.modules()
			.stream()
			.map(m -> new Module(m.modulePath(), m.reason(), m.depth(), m.affectedSymbols(), m.evidencePaths(), m.confidence()))
			.toList();
		List<TestLink> tests = result.tests()
			.stream()
			.map(t -> new TestLink(t.testSymbol(), t.filePath(), t.methods(), t.relation(), t.confidence(), t.line(),
					scope.url(t.filePath(), t.line(), null), t.via()))
			.toList();
		Diagram diagram = diagrams.chain(targetNode.id(), targetName, result);

		ImpactAnalysisDTO base = new ImpactAnalysisDTO(repositoryId, snapshotId, scope.runId(), scope.commitSha(), "OK", null,
				target, depth, components, endpoints, related, modules, tests, regressionAreas(result, targetName), riskScore,
				result.limitations(), result.truncated(), result.totalDependents(), diagram, null);
		if (!request.wantsAiSummary()) {
			return base;
		}
		return withSummary(base, ai.summarize(scope, base, request.changeDescription(), request.provider()));
	}

	public SymbolPickerDTO symbols(UUID repositoryId, UUID snapshotId, String query, int limit) {
		ImpactScope scope = scopes.resolve(repositoryId, snapshotId);
		if (picker.symbolCount(snapshotId, scope.runId()) == 0) {
			return new SymbolPickerDTO("NOT_SUPPORTED",
					"Impact analysis needs the symbol graph of the architecture analysis, which exists for Java and Spring "
							+ "repositories only. This snapshot has no symbols.",
					query, List.of());
		}
		return new SymbolPickerDTO("OK", null, query, picker.find(snapshotId, scope.runId(), query, limit));
	}

	public TargetSuggestionDTO suggestTargets(UUID repositoryId, UUID snapshotId, String description, String provider) {
		ImpactScope scope = scopes.resolve(repositoryId, snapshotId);
		return ai.suggest(scope, description, provider);
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private ImpactAnalysisDTO unsupported(ImpactScope scope, Node node, int depth) {
		Target target = new Target(node.id(), node.isType() ? "TYPE" : "METHOD", name(node), qualified(node), null, node.filePath(),
				node.startLine(), node.endLine(), scope.url(node.filePath(), node.startLine(), node.endLine()), node.modulePath());
		return new ImpactAnalysisDTO(scope.repositoryId(), scope.snapshotId(), scope.runId(), scope.commitSha(), "NOT_SUPPORTED",
				"No impact analyzer supports the language of " + node.filePath() + ".", target, depth, List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), null, List.of("Nothing was analysed for this symbol."), false, 0,
				new Diagram(null, null), null);
	}

	private static ImpactAnalysisDTO withSummary(ImpactAnalysisDTO dto, ImpactAnalysisDTO.AiSummary summary) {
		return new ImpactAnalysisDTO(dto.repositoryId(), dto.snapshotId(), dto.runId(), dto.commitSha(), dto.status(), dto.reason(),
				dto.target(), dto.depth(), dto.components(), dto.endpoints(), dto.relatedData(), dto.modules(), dto.tests(),
				dto.regressionAreas(), dto.risk(), dto.limitations(), dto.truncated(), dto.totalDependents(), dto.diagram(), summary);
	}

	/** Where to look after the change: endpoints reached, tests to run, the layers of direct dependents, dependent modules, gaps. */
	private static List<RegressionArea> regressionAreas(ImpactResult result, String targetName) {
		List<RegressionArea> areas = new ArrayList<>();
		for (ImpactResult.EndpointImpact endpoint : result.endpoints()) {
			areas.add(new RegressionArea("ENDPOINT", endpoint.httpMethod() + " " + endpoint.route(),
					"Its handler " + endpoint.handler() + " reaches " + targetName + ".", endpoint.confidence()));
		}
		for (ImpactResult.TestImpact test : result.tests()) {
			if (test.relation().equals("DIRECT_CALL") || test.relation().equals("NAMING_CONVENTION")) {
				areas.add(new RegressionArea("TEST", test.testSymbol(),
						test.relation().equals("DIRECT_CALL") ? "It calls the target" + (test.methods().isEmpty() ? "." : " in " + test.methods() + ".")
								: "It is named for the target's class.",
						test.confidence()));
			}
		}
		Map<String, List<String>> directByLayer = new java.util.TreeMap<>();
		result.components().stream().filter(c -> c.depth() == 1).forEach(c -> directByLayer
			.computeIfAbsent(c.layer() == null ? "OTHER" : c.layer(), k -> new ArrayList<>()).add(c.name()));
		directByLayer.forEach((layer, names) -> areas.add(new RegressionArea("COMPONENT",
				layer.toLowerCase(java.util.Locale.ROOT) + " code that depends directly on the target: "
						+ String.join(", ", names.stream().sorted().limit(6).toList()) + (names.size() > 6 ? ", ..." : ""),
				"These are the first places that break if the contract changes.", Confidence.DETECTED)));
		result.modules()
			.stream()
			.filter(m -> m.reason().equals("DEPENDS_ON_TARGET_MODULE"))
			.forEach(m -> areas.add(new RegressionArea("MODULE", m.modulePath(),
					"It declares a dependency on the target's module (Maven); build and test it.", m.confidence())));
		boolean covered = result.tests().stream().anyMatch(t -> t.relation().equals("DIRECT_CALL") || t.relation().equals("NAMING_CONVENTION"));
		if (!covered) {
			areas.add(new RegressionArea("GAP", "No test names " + targetName,
					"No test calls it or follows its naming convention: check the behaviour by hand or add a test.", Confidence.DETECTED));
		}
		return areas;
	}

	private static String name(Node node) {
		if (node.isType()) {
			return node.fqn().substring(node.fqn().lastIndexOf('.') + 1);
		}
		String owner = node.ownerName();
		return owner.substring(owner.lastIndexOf('.') + 1) + "." + (node.signature() != null ? node.signature() : node.simpleName());
	}

	private static String qualified(Node node) {
		return node.isType() ? node.fqn() : node.ownerName() + "#" + (node.signature() != null ? node.signature() : node.simpleName());
	}

}
