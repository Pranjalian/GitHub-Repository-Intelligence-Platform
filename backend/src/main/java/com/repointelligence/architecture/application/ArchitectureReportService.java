package com.repointelligence.architecture.application;

import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.AnalyzerCapability;
import com.repointelligence.analysis.domain.AnalyzerExecution;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerCapabilityRepository;
import com.repointelligence.analysis.infrastructure.persistence.AnalyzerExecutionRepository;
import com.repointelligence.analysis.registry.AnalyzerRegistry;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.architecture.api.ArchitectureReportDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.AnalyzerDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.ChainStepDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.CycleDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.DiagramsDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.EndpointDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.EntityDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.EntityRelationDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.InjectionDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.LayerClassDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.LayerDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.ModuleDependencyDTO;
import com.repointelligence.architecture.api.ArchitectureReportDTO.PatternDTO;
import com.repointelligence.architecture.application.ArchitectureDiagramService.Diagrams;
import com.repointelligence.architecture.application.ArchitectureDiagramService.EndpointNode;
import com.repointelligence.architecture.application.ArchitectureDiagramService.EntityLink;
import com.repointelligence.architecture.application.ArchitectureDiagramService.Link;
import com.repointelligence.architecture.application.ArchitectureDiagramService.ModuleLink;
import com.repointelligence.architecture.application.ArchitectureDiagramService.TypeNode;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.ApiEndpoint;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.CodeSymbol;
import com.repointelligence.architecture.domain.DependencyEdge;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.EntityRelationship;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import com.repointelligence.architecture.domain.LayerAssignment;
import com.repointelligence.architecture.domain.ModuleDependency;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.architecture.infrastructure.persistence.ApiEndpointRepository;
import com.repointelligence.architecture.infrastructure.persistence.CodeSymbolRepository;
import com.repointelligence.architecture.infrastructure.persistence.DependencyEdgeRepository;
import com.repointelligence.architecture.infrastructure.persistence.DependencyEdgeRepository.ResolutionCount;
import com.repointelligence.architecture.infrastructure.persistence.EntityRelationshipRepository;
import com.repointelligence.architecture.infrastructure.persistence.LayerAssignmentRepository;
import com.repointelligence.architecture.infrastructure.persistence.ModuleDependencyRepository;
import com.repointelligence.architecture.infrastructure.persistence.PatternCandidateRepository;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.report.application.ReportService;
import com.repointelligence.report.application.ReportService.ReportScope;
import com.repointelligence.report.application.SectionAggregator;
import com.repointelligence.report.application.SourceUrls;
import com.repointelligence.repository.domain.TrackedRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the commit-scoped architecture report from the stored parser facts and inferred descriptions.
 * Graph-derived views (dependency-injection chains, diagrams) are computed here from resolved edges
 * only, so nothing unresolved is ever drawn as a link.
 */
@Service
@Transactional(readOnly = true)
public class ArchitectureReportService {

	static final int MAX_CLASSES_PER_LAYER = 500;

	static final int MAX_CHAINS = 100;

	private static final int MAX_CHAIN_LENGTH = 6;

	private static final Set<String> CYCLE_RULES = Set.of("injection-cycle", "type-cycle");

	private final ReportService reports;

	private final AnalyzerExecutionRepository executions;

	private final AnalyzerCapabilityRepository capabilities;

	private final AnalysisFindingRepository findings;

	private final CodeSymbolRepository symbols;

	private final DependencyEdgeRepository edges;

	private final ApiEndpointRepository endpoints;

	private final EntityRelationshipRepository entityRelationships;

	private final LayerAssignmentRepository layers;

	private final ModuleDependencyRepository moduleDependencies;

	private final PatternCandidateRepository patterns;

	private final AnalyzerRegistry registry;

	private final ArchitectureDiagramService diagrams;

	public ArchitectureReportService(ReportService reports, AnalyzerExecutionRepository executions,
			AnalyzerCapabilityRepository capabilities, AnalysisFindingRepository findings, CodeSymbolRepository symbols,
			DependencyEdgeRepository edges, ApiEndpointRepository endpoints,
			EntityRelationshipRepository entityRelationships, LayerAssignmentRepository layers,
			ModuleDependencyRepository moduleDependencies, PatternCandidateRepository patterns, AnalyzerRegistry registry,
			ArchitectureDiagramService diagrams) {
		this.reports = reports;
		this.executions = executions;
		this.capabilities = capabilities;
		this.findings = findings;
		this.symbols = symbols;
		this.edges = edges;
		this.endpoints = endpoints;
		this.entityRelationships = entityRelationships;
		this.layers = layers;
		this.moduleDependencies = moduleDependencies;
		this.patterns = patterns;
		this.registry = registry;
		this.diagrams = diagrams;
	}

	public ArchitectureReportDTO report(UUID repositoryId, UUID snapshotId, UUID runId) {
		ReportScope scope = reports.scope(repositoryId, snapshotId, runId);
		UUID run = scope.run().getId();
		TrackedRepository repository = scope.snapshot().getRepository();
		String sha = scope.snapshot().getCommitSha();
		Urls urls = new Urls(repository.getOwner(), repository.getName(), sha);

		List<AnalyzerExecution> architectureExecutions = executions.findBySnapshotIdAndRunId(snapshotId, run)
			.stream()
			.filter(e -> e.getCategory() == AnalysisCategory.ARCHITECTURE)
			.sorted(Comparator.comparing(AnalyzerExecution::getAnalyzerId))
			.toList();
		List<AnalyzerCapability> matrix = capabilities.findBySnapshotIdAndRunId(snapshotId, run)
			.stream()
			.filter(c -> c.getCategory() == AnalysisCategory.ARCHITECTURE)
			.toList();
		List<String> unsupported = matrix.stream()
			.filter(c -> c.getAnalyzerId() == null)
			.map(c -> c.getTechnology() + ": " + c.getLimitationReason())
			.sorted()
			.toList();
		ExecutionStatus status = SectionAggregator
			.aggregate(architectureExecutions.stream().map(AnalyzerExecution::getStatus).toList());
		List<AnalyzerDTO> analyzers = architectureExecutions.stream()
			.map(e -> new AnalyzerDTO(e.getAnalyzerId(), e.getAnalyzerVersion(), e.getStatus(), level(matrix, e)))
			.toList();
		boolean supported = !architectureExecutions.isEmpty();

		// ── facts ──
		List<CodeSymbol> allSymbols = symbols.findBySnapshotIdAndRunId(snapshotId, run);
		Map<UUID, CodeSymbol> byId = new HashMap<>();
		allSymbols.forEach(s -> byId.put(s.getId(), s));
		Map<UUID, LayerAssignment> layerBySymbol = new HashMap<>();
		layers.findBySnapshotIdAndRunId(snapshotId, run).forEach(l -> layerBySymbol.put(l.getSymbol().getId(), l));
		List<DependencyEdge> injectEdges = edges.findBySnapshotIdAndRunIdAndRelationshipType(snapshotId, run,
				RelationshipType.INJECTS);
		List<EntityRelationship> relations = entityRelationships.findBySnapshotIdAndRunId(snapshotId, run);
		List<ApiEndpoint> apiEndpoints = endpoints.findBySnapshotIdAndRunId(snapshotId, run)
			.stream()
			.sorted(Comparator.comparing(ApiEndpoint::getRoute).thenComparing(ApiEndpoint::getHttpMethod))
			.toList();
		List<ModuleDependency> modules = moduleDependencies.findBySnapshotIdAndRunId(snapshotId, run);

		Map<String, Long> summary = summary(allSymbols, snapshotId, run, apiEndpoints, layerBySymbol);

		List<LayerDTO> layerDtos = layers(allSymbols, layerBySymbol, urls);
		List<EndpointDTO> endpointDtos = apiEndpoints.stream()
			.map(e -> new EndpointDTO(e.getHttpMethod(), e.getRoute(), e.isRouteResolved(), name(byId, e.getController()),
					signature(byId, e.getHandler()), e.getRequestType(), e.getResponseType(), e.getFilePath(),
					e.getLineNumber(), urls.of(e.getFilePath(), e.getLineNumber(), null)))
			.toList();
		List<EntityDTO> entityDtos = entities(allSymbols, layerBySymbol, relations, byId, urls);
		List<InjectionDTO> injections = injectEdges.stream()
			.map(e -> injection(e, byId, urls))
			.sorted(Comparator.comparing(InjectionDTO::from, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
				.thenComparing(InjectionDTO::to, Comparator.nullsFirst(Comparator.<String>naturalOrder())))
			.toList();
		List<List<ChainStepDTO>> chains = chains(injectEdges, relations, byId, layerBySymbol);
		List<ModuleDependencyDTO> moduleDtos = modules.stream()
			.sorted(Comparator.comparing(ModuleDependency::getSourceModulePath)
				.thenComparing(ModuleDependency::getTargetModulePath))
			.map(m -> new ModuleDependencyDTO(m.getSourceModulePath(), m.getTargetModulePath(),
					m.getEvidence() == null ? List.of() : m.getEvidence()))
			.toList();
		List<CycleDTO> cycles = findings.findBySnapshotIdAndRunId(snapshotId, run)
			.stream()
			.filter(f -> f.getCategory() == AnalysisCategory.ARCHITECTURE && CYCLE_RULES.contains(f.getRuleId()))
			.sorted(Comparator.comparing(AnalysisFinding::getSeverity).thenComparing(AnalysisFinding::getTitle))
			.map(f -> cycle(f, urls))
			.toList();
		List<PatternDTO> patternDtos = patterns.findBySnapshotIdAndRunId(snapshotId, run)
			.stream()
			.map(p -> new PatternDTO(p.getPattern(), p.getConfidence(),
					p.getEvidence() == null ? null : Objects.toString(p.getEvidence().get("summary"), null),
					p.getSymbolIds().stream().map(id -> byId.containsKey(id) ? byId.get(id).getFullyQualifiedName() : null)
						.filter(Objects::nonNull)
						.toList(),
					p.getEvidence() == null ? Map.of() : p.getEvidence()))
			.sorted(Comparator.comparing((PatternDTO p) -> p.pattern().name()).thenComparing(p -> String.valueOf(p.summary())))
			.toList();

		Diagrams generated = diagrams.generate(diagramInput(allSymbols, layerBySymbol, injectEdges, apiEndpoints,
				relations, modules));
		return new ArchitectureReportDTO(repositoryId, snapshotId, run, sha, supported,
				supported ? status : ExecutionStatus.NOT_SUPPORTED, analyzers, unsupported, summary, layerDtos, endpointDtos,
				entityDtos, injections, chains, moduleDtos, cycles, patternDtos,
				new DiagramsDTO(generated.layers(), generated.modules(), generated.apiFlow(), generated.entities(),
						generated.notes()),
				limitations(architectureExecutions, unsupported, summary));
	}

	// ── summary and limitations ──────────────────────────────────────────────────────────────────

	private Map<String, Long> summary(List<CodeSymbol> allSymbols, UUID snapshotId, UUID run,
			List<ApiEndpoint> apiEndpoints, Map<UUID, LayerAssignment> layerBySymbol) {
		Map<String, Long> summary = new LinkedHashMap<>();
		summary.put("types", allSymbols.stream().filter(s -> s.getSymbolType().isType()).count());
		summary.put("methods", allSymbols.stream().filter(s -> s.getSymbolType() == SymbolType.METHOD).count());
		summary.put("constructors", allSymbols.stream().filter(s -> s.getSymbolType() == SymbolType.CONSTRUCTOR).count());
		summary.put("fields", allSymbols.stream().filter(s -> s.getSymbolType() == SymbolType.FIELD).count());
		long total = 0;
		Map<EdgeResolution, Long> byResolution = new EnumMap<>(EdgeResolution.class);
		for (EdgeResolution resolution : EdgeResolution.values()) {
			byResolution.put(resolution, 0L);
		}
		for (ResolutionCount count : edges.countByResolution(snapshotId, run)) {
			byResolution.put(count.getResolution(), count.getTotal());
			total += count.getTotal();
		}
		summary.put("relationships", total);
		summary.put("resolvedRelationships", byResolution.get(EdgeResolution.RESOLVED));
		summary.put("unresolvedRelationships", byResolution.get(EdgeResolution.UNRESOLVED));
		summary.put("ambiguousRelationships", byResolution.get(EdgeResolution.AMBIGUOUS));
		summary.put("endpoints", (long) apiEndpoints.size());
		summary.put("layeredTypes", (long) layerBySymbol.size());
		summary.put("entities", layerBySymbol.values().stream().filter(l -> l.getLayer() == ArchitectureLayer.ENTITY).count());
		return summary;
	}

	private List<String> limitations(List<AnalyzerExecution> architectureExecutions, List<String> unsupported,
			Map<String, Long> summary) {
		Set<String> result = new LinkedHashSet<>();
		for (AnalyzerExecution execution : architectureExecutions) {
			if (execution.getStatus() != ExecutionStatus.COMPLETED) {
				result.add(execution.getAnalyzerId() + " ended as " + execution.getStatus()
						+ (execution.getErrorSummary() != null ? ": " + execution.getErrorSummary() : "."));
			}
			if (execution.getLimitations() != null) {
				result.addAll(execution.getLimitations());
			}
			registry.analyzers()
				.stream()
				.filter(a -> a.descriptor().id().equals(execution.getAnalyzerId()))
				.map(Analyzer::descriptor)
				.findFirst()
				.ifPresent(d -> result.addAll(d.limitations()));
		}
		long unresolved = summary.get("unresolvedRelationships") + summary.get("ambiguousRelationships");
		if (unresolved > 0) {
			result.add(unresolved + " of " + summary.get("relationships")
					+ " relationships are not resolved to a symbol of this commit; diagrams and chains use resolved ones only.");
		}
		unsupported.forEach(u -> result.add("Architecture not supported for " + u));
		return List.copyOf(result);
	}

	private static CapabilityLevel level(List<AnalyzerCapability> matrix, AnalyzerExecution execution) {
		return matrix.stream()
			.filter(c -> execution.getAnalyzerId().equals(c.getAnalyzerId()))
			.map(AnalyzerCapability::getCapabilityLevel)
			.findFirst()
			.orElse(null);
	}

	// ── layers and entities ──────────────────────────────────────────────────────────────────────

	private static List<LayerDTO> layers(List<CodeSymbol> allSymbols, Map<UUID, LayerAssignment> layerBySymbol,
			Urls urls) {
		Map<ArchitectureLayer, List<LayerClassDTO>> grouped = new EnumMap<>(ArchitectureLayer.class);
		for (CodeSymbol symbol : allSymbols) {
			LayerAssignment layer = layerBySymbol.get(symbol.getId());
			if (layer == null) {
				continue;
			}
			grouped.computeIfAbsent(layer.getLayer(), k -> new ArrayList<>())
				.add(new LayerClassDTO(simpleName(symbol.getFullyQualifiedName()), symbol.getFullyQualifiedName(),
						symbol.getFilePath(), symbol.getStartLine(), layer.getConfidence(), layer.getMethod(),
						layer.getEvidence() == null ? List.of() : layer.getEvidence(),
						urls.of(symbol.getFilePath(), symbol.getStartLine(), symbol.getEndLine())));
		}
		List<LayerDTO> result = new ArrayList<>();
		grouped.forEach((layer, classes) -> {
			List<LayerClassDTO> sorted = classes.stream()
				.sorted(Comparator.comparing((LayerClassDTO c) -> c.confidence().ordinal())
					.thenComparing(LayerClassDTO::fullyQualifiedName))
				.toList();
			result.add(new LayerDTO(layer, sorted.size(), sorted.stream().limit(MAX_CLASSES_PER_LAYER).toList()));
		});
		return result;
	}

	private static List<EntityDTO> entities(List<CodeSymbol> allSymbols, Map<UUID, LayerAssignment> layerBySymbol,
			List<EntityRelationship> relations, Map<UUID, CodeSymbol> byId, Urls urls) {
		List<EntityDTO> result = new ArrayList<>();
		for (CodeSymbol symbol : allSymbols) {
			LayerAssignment layer = layerBySymbol.get(symbol.getId());
			if (layer == null || layer.getLayer() != ArchitectureLayer.ENTITY) {
				continue;
			}
			List<EntityRelationDTO> relationships = relations.stream()
				.filter(r -> r.getRelationshipType() != EntityRelationshipType.REPOSITORY_FOR
						&& r.getSource().getId().equals(symbol.getId()))
				.sorted(Comparator.comparing(r -> String.valueOf(r.getFieldName())))
				.map(r -> new EntityRelationDTO(r.getFieldName(), r.getRelationshipType(), target(r, byId),
						r.getTarget() != null, r.getAttributes() == null ? Map.of() : r.getAttributes()))
				.toList();
			List<String> repositories = relations.stream()
				.filter(r -> r.getRelationshipType() == EntityRelationshipType.REPOSITORY_FOR && r.getTarget() != null
						&& r.getTarget().getId().equals(symbol.getId()))
				.map(r -> name(byId, r.getSource()))
				.sorted()
				.toList();
			result.add(new EntityDTO(simpleName(symbol.getFullyQualifiedName()), symbol.getFullyQualifiedName(),
					table(symbol), symbol.getFilePath(), symbol.getStartLine(),
					urls.of(symbol.getFilePath(), symbol.getStartLine(), symbol.getEndLine()), relationships, repositories));
		}
		result.sort(Comparator.comparing(EntityDTO::fullyQualifiedName));
		return result;
	}

	private static String table(CodeSymbol symbol) {
		if (symbol.getAnnotations() == null) {
			return null;
		}
		return symbol.getAnnotations()
			.stream()
			.filter(a -> a.simpleName().equals("Table"))
			.map(AnnotationInfo::attributes)
			.map(attributes -> attributes.get("name"))
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(null);
	}

	private static String target(EntityRelationship relation, Map<UUID, CodeSymbol> byId) {
		return relation.getTarget() != null ? name(byId, relation.getTarget()) : relation.getTargetUnresolvedName();
	}

	// ── injection and chains ─────────────────────────────────────────────────────────────────────

	private static InjectionDTO injection(DependencyEdge edge, Map<UUID, CodeSymbol> byId, Urls urls) {
		Map<String, Object> evidence = edge.getEvidence() == null ? Map.of() : edge.getEvidence();
		String file = Objects.toString(evidence.get("file"), null);
		Integer line = evidence.get("line") instanceof Number n ? n.intValue() : null;
		Confidence confidence = evidence.get("confidence") instanceof String c ? Confidence.valueOf(c) : null;
		String to = edge.getTarget() != null ? name(byId, edge.getTarget()) : edge.getTargetUnresolvedName();
		return new InjectionDTO(name(byId, edge.getSource()), to, edge.getResolution(), confidence,
				Objects.toString(evidence.get("via"), null), Objects.toString(evidence.get("member"), null), file, line,
				urls.of(file, line, null));
	}

	/**
	 * Controller → service(s) → repository → entity paths over resolved injection edges and repository
	 * mappings. A chain stops when the next type has no layer on the way down or would repeat.
	 */
	private static List<List<ChainStepDTO>> chains(List<DependencyEdge> injectEdges, List<EntityRelationship> relations,
			Map<UUID, CodeSymbol> byId, Map<UUID, LayerAssignment> layerBySymbol) {
		Map<UUID, Set<UUID>> next = new LinkedHashMap<>();
		for (DependencyEdge edge : injectEdges) {
			if (edge.getResolution() == EdgeResolution.RESOLVED && edge.getTarget() != null) {
				next.computeIfAbsent(edge.getSource().getId(), k -> new LinkedHashSet<>()).add(edge.getTarget().getId());
			}
		}
		Map<UUID, Set<UUID>> manages = new HashMap<>();
		for (EntityRelationship relation : relations) {
			if (relation.getRelationshipType() == EntityRelationshipType.REPOSITORY_FOR && relation.getTarget() != null) {
				manages.computeIfAbsent(relation.getSource().getId(), k -> new LinkedHashSet<>()).add(relation.getTarget().getId());
			}
		}
		List<List<ChainStepDTO>> result = new ArrayList<>();
		List<UUID> controllers = layerBySymbol.values()
			.stream()
			.filter(l -> l.getLayer() == ArchitectureLayer.CONTROLLER)
			.map(l -> l.getSymbol().getId())
			.sorted(Comparator.comparing(id -> byId.get(id).getFullyQualifiedName()))
			.toList();
		for (UUID controller : controllers) {
			walk(List.of(controller), next, manages, byId, layerBySymbol, result);
			if (result.size() >= MAX_CHAINS) {
				break;
			}
		}
		return result.stream().limit(MAX_CHAINS).toList();
	}

	private static void walk(List<UUID> path, Map<UUID, Set<UUID>> next, Map<UUID, Set<UUID>> manages,
			Map<UUID, CodeSymbol> byId, Map<UUID, LayerAssignment> layerBySymbol, List<List<ChainStepDTO>> out) {
		if (out.size() >= MAX_CHAINS) {
			return;
		}
		UUID last = path.get(path.size() - 1);
		ArchitectureLayer layer = layerOf(layerBySymbol, last);
		List<UUID> successors = new ArrayList<>();
		if (path.size() < MAX_CHAIN_LENGTH) {
			if (layer == ArchitectureLayer.REPOSITORY) {
				successors.addAll(manages.getOrDefault(last, Set.of()));
			}
			else if (layer == ArchitectureLayer.CONTROLLER || layer == ArchitectureLayer.SERVICE) {
				for (UUID candidate : next.getOrDefault(last, Set.of())) {
					ArchitectureLayer target = layerOf(layerBySymbol, candidate);
					if ((target == ArchitectureLayer.SERVICE || target == ArchitectureLayer.REPOSITORY)
							&& !path.contains(candidate)) {
						successors.add(candidate);
					}
				}
			}
		}
		if (successors.isEmpty()) {
			if (path.size() > 1) {
				out.add(path.stream()
					.map(id -> new ChainStepDTO(simpleName(byId.get(id).getFullyQualifiedName()),
							byId.get(id).getFullyQualifiedName(), layerOf(layerBySymbol, id)))
					.toList());
			}
			return;
		}
		for (UUID successor : successors) {
			List<UUID> extended = new ArrayList<>(path);
			extended.add(successor);
			walk(extended, next, manages, byId, layerBySymbol, out);
		}
	}

	private static ArchitectureLayer layerOf(Map<UUID, LayerAssignment> layerBySymbol, UUID id) {
		LayerAssignment layer = layerBySymbol.get(id);
		return layer == null ? null : layer.getLayer();
	}

	// ── cycles ───────────────────────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static CycleDTO cycle(AnalysisFinding finding, Urls urls) {
		Map<String, Object> evidence = finding.getEvidence() == null ? Map.of() : finding.getEvidence();
		List<String> members = evidence.get("members") instanceof List<?> list
				? list.stream().map(String::valueOf).toList() : List.of();
		List<Map<String, Object>> cycleEdges = evidence.get("edges") instanceof List<?> list
				? list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList() : List.of();
		return new CycleDTO(finding.getRuleId(), finding.getSeverity(), finding.getConfidence(), finding.getTitle(),
				finding.getDescription(), members, cycleEdges, finding.getFilePath(), finding.getStartLine(),
				urls.of(finding.getFilePath(), finding.getStartLine(), null));
	}

	// ── diagrams ─────────────────────────────────────────────────────────────────────────────────

	/** Sorted by name throughout so that the same commit always gives the same diagram source. */
	private static ArchitectureDiagramService.Input diagramInput(List<CodeSymbol> allSymbols,
			Map<UUID, LayerAssignment> layerBySymbol, List<DependencyEdge> injectEdges, List<ApiEndpoint> apiEndpoints,
			List<EntityRelationship> relations, List<ModuleDependency> modules) {
		Map<UUID, String> fqn = new HashMap<>();
		allSymbols.forEach(s -> fqn.put(s.getId(), s.getFullyQualifiedName()));
		List<TypeNode> types = allSymbols.stream()
			.filter(s -> s.getSymbolType().isType())
			.sorted(Comparator.comparing(CodeSymbol::getFullyQualifiedName).thenComparing(CodeSymbol::getFilePath))
			.map(s -> new TypeNode(s.getId(), s.getFullyQualifiedName(), simpleName(s.getFullyQualifiedName()),
					layerOf(layerBySymbol, s.getId())))
			.toList();
		Set<String> seen = new HashSet<>();
		List<Link> links = injectEdges.stream()
			.filter(e -> e.getResolution() == EdgeResolution.RESOLVED && e.getTarget() != null)
			.map(e -> new Link(e.getSource().getId(), e.getTarget().getId()))
			.filter(l -> seen.add(l.from() + ">" + l.to()))
			.sorted(Comparator.comparing((Link l) -> String.valueOf(fqn.get(l.from())))
				.thenComparing(l -> String.valueOf(fqn.get(l.to()))))
			.toList();
		List<EndpointNode> endpointNodes = apiEndpoints.stream()
			.map(e -> new EndpointNode(e.getHttpMethod(), e.getRoute(), e.getController().getId()))
			.toList();
		List<EntityLink> entityLinks = relations.stream()
			.map(r -> new EntityLink(r.getSource().getId(), r.getTarget() == null ? null : r.getTarget().getId(),
					r.getRelationshipType(), r.getFieldName()))
			.sorted(Comparator.comparing((EntityLink l) -> String.valueOf(fqn.get(l.from())))
				.thenComparing(l -> l.type().name())
				.thenComparing(l -> String.valueOf(l.field())))
			.toList();
		List<ModuleLink> moduleLinks = modules.stream()
			.map(m -> new ModuleLink(m.getSourceModulePath(), m.getTargetModulePath()))
			.sorted(Comparator.comparing(ModuleLink::from).thenComparing(ModuleLink::to))
			.collect(Collectors.toList());
		return new ArchitectureDiagramService.Input(types, links, endpointNodes, entityLinks, moduleLinks);
	}

	// ── names ────────────────────────────────────────────────────────────────────────────────────

	private static String name(Map<UUID, CodeSymbol> byId, CodeSymbol reference) {
		CodeSymbol symbol = reference == null ? null : byId.get(reference.getId());
		return symbol == null ? null : symbol.getFullyQualifiedName();
	}

	private static String signature(Map<UUID, CodeSymbol> byId, CodeSymbol reference) {
		CodeSymbol symbol = reference == null ? null : byId.get(reference.getId());
		if (symbol == null) {
			return null;
		}
		String fqn = symbol.getFullyQualifiedName();
		String owner = fqn.contains("#") ? fqn.substring(0, fqn.indexOf('#')) : fqn;
		return simpleName(owner) + "." + (symbol.getSignature() != null ? symbol.getSignature() : fqn);
	}

	static String simpleName(String fqn) {
		String type = fqn.contains("#") ? fqn.substring(0, fqn.indexOf('#')) : fqn;
		int dot = type.lastIndexOf('.');
		return dot >= 0 ? type.substring(dot + 1) : type;
	}

	/** GitHub permalinks for one commit. */
	private record Urls(String owner, String name, String sha) {

		String of(String path, Integer start, Integer end) {
			return path == null ? null : SourceUrls.blobUrl(owner, name, sha, path, start, end);
		}

	}

}
