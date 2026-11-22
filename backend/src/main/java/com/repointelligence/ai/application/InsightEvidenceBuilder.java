package com.repointelligence.ai.application;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.ModuleRecord;
import com.repointelligence.analysis.domain.TechnologyFinding;
import com.repointelligence.analysis.generic.GenericSecretScanAnalyzer;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisFindingRepository;
import com.repointelligence.analysis.infrastructure.persistence.DependencyRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.ModuleRecordRepository;
import com.repointelligence.analysis.infrastructure.persistence.TechnologyFindingRepository;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.ApiEndpoint;
import com.repointelligence.architecture.domain.CodeSymbol;
import com.repointelligence.architecture.domain.DependencyEdge;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.LayerAssignment;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.architecture.infrastructure.persistence.ApiEndpointRepository;
import com.repointelligence.architecture.infrastructure.persistence.CodeSymbolRepository;
import com.repointelligence.architecture.infrastructure.persistence.DependencyEdgeRepository;
import com.repointelligence.architecture.infrastructure.persistence.LayerAssignmentRepository;
import com.repointelligence.common.exception.BadRequestException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.Severity;
import com.repointelligence.report.application.ReportService;
import com.repointelligence.report.application.SourceUrls;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collects the evidence for one insight from the stored analysis of a single run: parser facts (symbols,
 * edges, layers, endpoints), findings, modules and dependencies. It never reads source itself; it says
 * which line ranges are needed ({@link SourceNeed}) and the insight service reads them outside the
 * transaction. Everything is ordered deterministically, so the same commit gives the same prompt (and
 * so the same cache key). Bounded: at most {@value #MAX_RELATED} related types, {@value #MAX_FINDINGS}
 * findings, {@value #MAX_MEMBERS} members per type.
 */
@Service
@Transactional(readOnly = true)
public class InsightEvidenceBuilder {

	static final int MAX_RELATED = 6;

	static final int MAX_FINDINGS = 15;

	static final int MAX_MEMBERS = 40;

	static final int MAX_DEBT_GROUPS = 60;

	static final int DEBT_GROUPS_PER_BLOCK = 10;

	static final int FINDING_CONTEXT_LINES = 12;

	/** Most informative relationships first. */
	private static final List<RelationshipType> RELATED_ORDER = List.of(RelationshipType.EXTENDS,
			RelationshipType.IMPLEMENTS, RelationshipType.INJECTS, RelationshipType.FIELD_TYPE, RelationshipType.CALLS,
			RelationshipType.PARAM_TYPE, RelationshipType.RETURN_TYPE);

	private final CodeSymbolRepository symbols;

	private final DependencyEdgeRepository edges;

	private final LayerAssignmentRepository layers;

	private final ApiEndpointRepository endpoints;

	private final AnalysisFindingRepository findings;

	private final ModuleRecordRepository modules;

	private final DependencyRecordRepository dependencies;

	private final TechnologyFindingRepository technologies;

	private final ReportService reports;

	public InsightEvidenceBuilder(ReportService reports, CodeSymbolRepository symbols, DependencyEdgeRepository edges,
			LayerAssignmentRepository layers, ApiEndpointRepository endpoints, AnalysisFindingRepository findings,
			ModuleRecordRepository modules, DependencyRecordRepository dependencies,
			TechnologyFindingRepository technologies) {
		this.reports = reports;
		this.symbols = symbols;
		this.edges = edges;
		this.layers = layers;
		this.endpoints = endpoints;
		this.findings = findings;
		this.modules = modules;
		this.dependencies = dependencies;
		this.technologies = technologies;
	}

	/**
	 * Where the evidence comes from: one run of one snapshot, as plain values (no lazy entities).
	 *
	 * @param visibility GitHub visibility of the repository ({@code public} for every repository today)
	 */
	public record Scope(UUID repositoryId, UUID snapshotId, UUID runId, String owner, String repo, String commitSha,
			String visibility) {

		String url(String path, Integer start, Integer end) {
			return SourceUrls.blobUrl(owner, repo, commitSha, path, start, end);
		}

	}

	/** A line range the service must read from the snapshot's source. */
	public record SourceNeed(String kind, String filePath, int startLine, int endLine) {
	}

	/**
	 * The evidence plan: the target block, source to read (inserted right after the target), the other
	 * blocks in priority order, and limitations to report.
	 *
	 * @param findingsFirst refactoring suggestions put findings before declarations
	 */
	public record Plan(String targetLabel, EvidenceBlock target, List<SourceNeed> sources, List<EvidenceBlock> findings,
			List<EvidenceBlock> declarations, List<String> limitations, boolean findingsFirst) {
	}

	/**
	 * Resolves the snapshot exactly like every report endpoint (404 unless it belongs to the repository; the
	 * latest completed run), inside this read-only transaction.
	 */
	public Scope scope(UUID repositoryId, UUID snapshotId) {
		ReportService.ReportScope scope = reports.scope(repositoryId, snapshotId, null);
		var repository = scope.snapshot().getRepository();
		return new Scope(repositoryId, snapshotId, scope.run().getId(), repository.getOwner(), repository.getName(),
				scope.snapshot().getCommitSha(), repository.getVisibility());
	}

	public Plan plan(InsightType type, String targetId, Scope scope, int maxSourceLines) {
		return switch (type) {
			case EXPLAIN_CLASS -> typePlan(typeSymbol(targetId, scope), scope, maxSourceLines, false);
			case EXPLAIN_METHOD -> methodPlan(memberSymbol(targetId, scope), scope, maxSourceLines, false);
			case SUGGEST_REFACTORING -> {
				CodeSymbol symbol = symbol(targetId, scope);
				if (symbol.getSymbolType().isType()) {
					yield typePlan(symbol, scope, maxSourceLines, true);
				}
				if (symbol.getSymbolType() == SymbolType.METHOD || symbol.getSymbolType() == SymbolType.CONSTRUCTOR) {
					yield methodPlan(symbol, scope, maxSourceLines, true);
				}
				throw new BadRequestException("SUGGEST_REFACTORING needs a type, method or constructor symbol.");
			}
			case EXPLAIN_FINDING -> findingPlan(targetId, scope);
			case SUMMARIZE_MODULE -> modulePlan(targetId, scope);
			case TECH_DEBT_SUMMARY -> techDebtPlan(scope);
		};
	}

	// ── targets ──────────────────────────────────────────────────────────────────────────────────

	private CodeSymbol symbol(String targetId, Scope scope) {
		UUID id = uuid(targetId, "symbol");
		CodeSymbol symbol = symbols.findById(id)
			.filter(s -> s.getSnapshot().getId().equals(scope.snapshotId()) && s.getRun().getId().equals(scope.runId()))
			.orElseThrow(() -> new ResourceNotFoundException(
					"Symbol not found in the latest completed analysis of this snapshot."));
		return symbol;
	}

	private CodeSymbol typeSymbol(String targetId, Scope scope) {
		CodeSymbol symbol = symbol(targetId, scope);
		if (!symbol.getSymbolType().isType()) {
			throw new BadRequestException("EXPLAIN_CLASS needs a type symbol (class, interface, enum, record, annotation).");
		}
		return symbol;
	}

	private CodeSymbol memberSymbol(String targetId, Scope scope) {
		CodeSymbol symbol = symbol(targetId, scope);
		if (symbol.getSymbolType() != SymbolType.METHOD && symbol.getSymbolType() != SymbolType.CONSTRUCTOR) {
			throw new BadRequestException("EXPLAIN_METHOD needs a method or constructor symbol.");
		}
		return symbol;
	}

	private static UUID uuid(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new BadRequestException("targetId (a " + what + " id) is required for this insight type.");
		}
		try {
			return UUID.fromString(value.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new BadRequestException("targetId must be a " + what + " id (UUID).");
		}
	}

	// ── types and methods ────────────────────────────────────────────────────────────────────────

	private Plan typePlan(CodeSymbol type, Scope scope, int maxSourceLines, boolean refactoring) {
		List<CodeSymbol> members = sortedMembers(type, scope);
		List<UUID> ownIds = new ArrayList<>();
		ownIds.add(type.getId());
		members.forEach(m -> ownIds.add(m.getId()));
		List<DependencyEdge> outgoing = edges.findBySnapshotIdAndRunIdAndSourceIdIn(scope.snapshotId(), scope.runId(), ownIds);
		List<CodeSymbol> related = relatedTypes(outgoing, type);
		Map<UUID, LayerAssignment> layerOf = layersOf(type, related, scope);
		List<String> limitations = new ArrayList<>();

		StringBuilder target = new StringBuilder(declaration(type, layerOf.get(type.getId())));
		List<ApiEndpoint> handled = endpoints.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(e -> e.getController().getId().equals(type.getId()))
			.sorted(Comparator.comparing(ApiEndpoint::getRoute).thenComparing(ApiEndpoint::getHttpMethod))
			.toList();
		if (!handled.isEmpty()) {
			target.append("REST endpoints:\n");
			handled.forEach(e -> target.append("  ").append(endpointLine(e)).append('\n'));
		}
		appendMembers(target, members);
		appendUnresolved(target, outgoing);

		List<SourceNeed> sources = new ArrayList<>();
		if (type.getStartLine() != null) {
			int end = type.getEndLine() == null ? type.getStartLine() + maxSourceLines - 1 : type.getEndLine();
			if (end - type.getStartLine() + 1 > maxSourceLines) {
				limitations.add("The source of " + simple(type.getFullyQualifiedName()) + " is longer than " + maxSourceLines
						+ " lines; only the first " + maxSourceLines + " are sent.");
				end = type.getStartLine() + maxSourceLines - 1;
			}
			sources.add(new SourceNeed("SOURCE", type.getFilePath(), type.getStartLine(), end));
		}
		List<EvidenceBlock> declarations = new ArrayList<>();
		for (CodeSymbol relatedType : related) {
			declarations.add(block("DECLARATION", relatedType.getFilePath(), relatedType.getStartLine(),
					relatedType.getEndLine(), declaration(relatedType, layerOf.get(relatedType.getId()))
							+ memberSummary(sortedMembers(relatedType, scope)),
					scope));
		}
		if (related.size() == MAX_RELATED) {
			limitations.add("Only the " + MAX_RELATED + " most directly related types are included.");
		}
		List<EvidenceBlock> findingBlocks = findingsIn(type.getFilePath(), type.getStartLine(), type.getEndLine(), scope,
				refactoring);
		return new Plan(simple(type.getFullyQualifiedName()),
				block("TARGET", type.getFilePath(), type.getStartLine(), type.getEndLine(), target.toString(), scope), sources,
				findingBlocks, declarations, limitations, refactoring);
	}

	private Plan methodPlan(CodeSymbol method, Scope scope, int maxSourceLines, boolean refactoring) {
		CodeSymbol owner = method.getParent();
		List<DependencyEdge> outgoing = edges.findBySnapshotIdAndRunIdAndSourceIdIn(scope.snapshotId(), scope.runId(),
				List.of(method.getId()));
		List<CodeSymbol> related = relatedTypes(outgoing, owner);
		List<String> limitations = new ArrayList<>();

		StringBuilder target = new StringBuilder();
		target.append(method.getSymbolType().name().toLowerCase(java.util.Locale.ROOT)).append(' ')
			.append(memberName(method)).append('\n');
		if (owner != null) {
			target.append("declared in: ").append(owner.getFullyQualifiedName()).append('\n');
		}
		target.append("file: ").append(method.getFilePath()).append(lines(method)).append('\n');
		appendAnnotations(target, method);
		endpoints.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(e -> e.getHandler().getId().equals(method.getId()))
			.sorted(Comparator.comparing(ApiEndpoint::getRoute))
			.forEach(e -> target.append("handles REST endpoint: ").append(endpointLine(e)).append('\n'));
		appendUnresolved(target, outgoing);

		List<SourceNeed> sources = new ArrayList<>();
		if (method.getStartLine() != null) {
			int end = method.getEndLine() == null ? method.getStartLine() : method.getEndLine();
			if (end - method.getStartLine() + 1 > maxSourceLines) {
				limitations.add("The method is longer than " + maxSourceLines + " lines; only the first " + maxSourceLines
						+ " are sent.");
				end = method.getStartLine() + maxSourceLines - 1;
			}
			sources.add(new SourceNeed("SOURCE", method.getFilePath(), method.getStartLine(), end));
		}
		List<EvidenceBlock> declarations = new ArrayList<>();
		Map<UUID, LayerAssignment> layerOf = layersOf(owner, related, scope);
		if (owner != null) {
			declarations.add(block("DECLARATION", owner.getFilePath(), owner.getStartLine(), owner.getEndLine(),
					declaration(owner, layerOf.get(owner.getId())) + memberSummary(sortedMembers(owner, scope)), scope));
		}
		for (CodeSymbol relatedType : related) {
			declarations.add(block("DECLARATION", relatedType.getFilePath(), relatedType.getStartLine(),
					relatedType.getEndLine(), declaration(relatedType, layerOf.get(relatedType.getId()))
							+ memberSummary(sortedMembers(relatedType, scope)),
					scope));
		}
		String label = (owner == null ? "" : simple(owner.getFullyQualifiedName()) + ".") + memberName(method);
		return new Plan(label,
				block("TARGET", method.getFilePath(), method.getStartLine(), method.getEndLine(), target.toString(), scope),
				sources, findingsIn(method.getFilePath(), method.getStartLine(), method.getEndLine(), scope, refactoring),
				declarations, limitations, refactoring);
	}

	private List<CodeSymbol> sortedMembers(CodeSymbol type, Scope scope) {
		if (type == null) {
			return List.of();
		}
		return symbols.findBySnapshotIdAndRunIdAndParentId(scope.snapshotId(), scope.runId(), type.getId())
			.stream()
			.sorted(Comparator.comparing((CodeSymbol s) -> s.getStartLine() == null ? Integer.MAX_VALUE : s.getStartLine())
				.thenComparing(CodeSymbol::getFullyQualifiedName))
			.toList();
	}

	/** Resolved targets of the edges, as types (a called method counts as its type), most informative first. */
	private static List<CodeSymbol> relatedTypes(List<DependencyEdge> outgoing, CodeSymbol self) {
		Map<UUID, CodeSymbol> ordered = new LinkedHashMap<>();
		for (RelationshipType relationship : RELATED_ORDER) {
			outgoing.stream()
				.filter(e -> e.getRelationshipType() == relationship && e.getResolution() == EdgeResolution.RESOLVED
						&& e.getTarget() != null)
				.map(e -> e.getTarget().getSymbolType().isType() ? e.getTarget() : e.getTarget().getParent())
				.filter(Objects::nonNull)
				.filter(t -> self == null || !t.getId().equals(self.getId()))
				.sorted(Comparator.comparing(CodeSymbol::getFullyQualifiedName))
				.forEach(t -> ordered.putIfAbsent(t.getId(), t));
		}
		return ordered.values().stream().limit(MAX_RELATED).toList();
	}

	private Map<UUID, LayerAssignment> layersOf(CodeSymbol main, List<CodeSymbol> related, Scope scope) {
		Set<UUID> ids = new LinkedHashSet<>();
		if (main != null) {
			ids.add(main.getId());
		}
		related.forEach(r -> ids.add(r.getId()));
		Map<UUID, LayerAssignment> result = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return result;
		}
		for (LayerAssignment layer : layers.findBySnapshotIdAndRunIdAndSymbolIdIn(scope.snapshotId(), scope.runId(), ids)) {
			result.merge(layer.getSymbol().getId(), layer,
					(a, b) -> a.getConfidence().ordinal() <= b.getConfidence().ordinal() ? a : b);
		}
		return result;
	}

	private static String declaration(CodeSymbol type, LayerAssignment layer) {
		StringBuilder text = new StringBuilder();
		text.append(type.getSymbolType().name().toLowerCase(java.util.Locale.ROOT)).append(' ')
			.append(type.getFullyQualifiedName()).append('\n');
		text.append("file: ").append(type.getFilePath()).append(lines(type)).append('\n');
		if (type.getModulePath() != null) {
			text.append("module: ").append(type.getModulePath()).append('\n');
		}
		if (type.getModifiers() != null && !type.getModifiers().isEmpty()) {
			text.append("modifiers: ").append(String.join(" ", type.getModifiers())).append('\n');
		}
		appendAnnotations(text, type);
		if (layer != null) {
			text.append("layer: ").append(layer.getLayer()).append(" (").append(layer.getConfidence()).append(", ")
				.append(layer.getMethod().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')).append(")\n");
		}
		return text.toString();
	}

	private static void appendAnnotations(StringBuilder text, CodeSymbol symbol) {
		if (symbol.getAnnotations() == null || symbol.getAnnotations().isEmpty()) {
			return;
		}
		text.append("annotations: ")
			.append(symbol.getAnnotations().stream().map(InsightEvidenceBuilder::annotation).collect(Collectors.joining(" ")))
			.append('\n');
	}

	private static String annotation(AnnotationInfo info) {
		if (info.attributes().isEmpty()) {
			return "@" + info.simpleName();
		}
		return "@" + info.simpleName() + new TreeMap<>(info.attributes()).entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.joining(", ", "(", ")"));
	}

	private static void appendMembers(StringBuilder text, List<CodeSymbol> members) {
		if (members.isEmpty()) {
			return;
		}
		text.append("members:\n");
		members.stream().limit(MAX_MEMBERS).forEach(m -> text.append("  ").append(memberLine(m)).append('\n'));
		if (members.size() > MAX_MEMBERS) {
			text.append("  ... ").append(members.size() - MAX_MEMBERS).append(" more\n");
		}
	}

	private static String memberSummary(List<CodeSymbol> members) {
		StringBuilder text = new StringBuilder();
		appendMembers(text, members);
		return text.toString();
	}

	private static String memberLine(CodeSymbol member) {
		StringBuilder line = new StringBuilder(member.getSymbolType().name().toLowerCase(java.util.Locale.ROOT)).append(' ')
			.append(memberName(member));
		if (member.getSymbolType() == SymbolType.FIELD && member.getSignature() != null) {
			line.append(" : ").append(member.getSignature());
		}
		if (member.getAnnotations() != null && !member.getAnnotations().isEmpty()) {
			line.append(' ')
				.append(member.getAnnotations().stream().map(a -> "@" + a.simpleName()).collect(Collectors.joining(" ")));
		}
		return line.append(lines(member)).toString();
	}

	/** {@code process(Order)} for methods (from the signature), the simple name otherwise. */
	private static String memberName(CodeSymbol member) {
		if ((member.getSymbolType() == SymbolType.METHOD || member.getSymbolType() == SymbolType.CONSTRUCTOR)
				&& member.getSignature() != null) {
			return member.getSignature();
		}
		String fqn = member.getFullyQualifiedName();
		int hash = fqn.indexOf('#');
		return hash >= 0 ? fqn.substring(hash + 1) : simple(fqn);
	}

	private static void appendUnresolved(StringBuilder text, List<DependencyEdge> outgoing) {
		List<String> names = outgoing.stream()
			.filter(e -> e.getResolution() != EdgeResolution.RESOLVED && e.getTargetUnresolvedName() != null)
			.filter(e -> e.getRelationshipType() != RelationshipType.IMPORTS)
			.map(e -> e.getTargetUnresolvedName() + (e.getResolution() == EdgeResolution.AMBIGUOUS ? " (ambiguous)" : ""))
			.distinct()
			.sorted()
			.toList();
		if (names.isEmpty()) {
			return;
		}
		text.append("uses types outside this repository (not resolved, names as written): ")
			.append(String.join(", ", names.subList(0, Math.min(15, names.size()))))
			.append(names.size() > 15 ? ", ..." : "")
			.append('\n');
	}

	private static String endpointLine(ApiEndpoint e) {
		return e.getHttpMethod() + " " + e.getRoute() + (e.isRouteResolved() ? "" : " (path partly unresolved)")
				+ (e.getRequestType() != null ? " request " + simple(e.getRequestType()) : "")
				+ (e.getResponseType() != null ? " response " + simple(e.getResponseType()) : "");
	}

	// ── findings ─────────────────────────────────────────────────────────────────────────────────

	private List<EvidenceBlock> findingsIn(String path, Integer start, Integer end, Scope scope, boolean qualityFirst) {
		List<AnalysisFinding> inRange = findings.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(f -> path.equals(f.getFilePath()) && f.getCategory() != AnalysisCategory.STRUCTURE)
			.filter(f -> start == null || f.getStartLine() == null
					|| (f.getStartLine() >= start && (end == null || f.getStartLine() <= end)))
			.sorted(Comparator
				.comparing((AnalysisFinding f) -> qualityFirst && f.getCategory() == AnalysisCategory.CODE_QUALITY ? 0 : 1)
				.thenComparing(f -> f.getSeverity().ordinal())
				.thenComparing(f -> f.getStartLine() == null ? 0 : f.getStartLine())
				.thenComparing(AnalysisFinding::getRuleId))
			.toList();
		if (inRange.isEmpty()) {
			return List.of();
		}
		StringBuilder text = new StringBuilder("Static-analysis findings in this code (from deterministic tools):\n");
		inRange.stream().limit(MAX_FINDINGS).forEach(f -> text.append("- ").append(findingLine(f)).append('\n'));
		if (inRange.size() > MAX_FINDINGS) {
			text.append("- ... ").append(inRange.size() - MAX_FINDINGS).append(" more\n");
		}
		return List.of(block("FINDINGS", null, null, null, text.toString(), scope));
	}

	private static String findingLine(AnalysisFinding f) {
		return "[" + f.getSeverity() + ", " + f.getConfidence() + "] " + f.getCategory() + " rule " + f.getRuleId() + " ("
				+ f.getAnalyzerId() + ")" + (f.getStartLine() != null ? " at line " + f.getStartLine() : "") + ": "
				+ f.getTitle();
	}

	private Plan findingPlan(String targetId, Scope scope) {
		UUID id = uuid(targetId, "finding");
		AnalysisFinding finding = findings.findById(id)
			.filter(f -> f.getSnapshot().getId().equals(scope.snapshotId()) && f.getRun().getId().equals(scope.runId()))
			.orElseThrow(() -> new ResourceNotFoundException(
					"Finding not found in the latest completed analysis of this snapshot."));
		StringBuilder target = new StringBuilder();
		target.append("title: ").append(finding.getTitle()).append('\n');
		target.append("category: ").append(finding.getCategory()).append('\n');
		target.append("rule: ").append(finding.getRuleId()).append('\n');
		target.append("severity: ").append(finding.getSeverity()).append(" (as reported by the tool)\n");
		target.append("confidence: ").append(finding.getConfidence()).append('\n');
		target.append("reported by: ").append(finding.getAnalyzerId()).append(" (")
			.append(finding.getAnalysisMethod().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')).append(")\n");
		if (finding.getFilePath() != null) {
			target.append("location: ").append(finding.getFilePath());
			if (finding.getStartLine() != null) {
				target.append(" line ").append(finding.getStartLine());
				if (finding.getEndLine() != null && !finding.getEndLine().equals(finding.getStartLine())) {
					target.append('-').append(finding.getEndLine());
				}
			}
			target.append('\n');
		}
		if (finding.getDescription() != null) {
			target.append("description: ").append(finding.getDescription()).append('\n');
		}
		if (finding.getSuggestedNextStep() != null) {
			target.append("tool's suggested next step: ").append(finding.getSuggestedNextStep()).append('\n');
		}
		if (finding.getEvidence() != null && !finding.getEvidence().isEmpty()) {
			target.append("tool evidence:\n");
			new TreeMap<>(finding.getEvidence()).forEach((key, value) -> target.append("  ").append(key).append(": ")
				.append(String.valueOf(value)).append('\n'));
		}
		List<SourceNeed> sources = new ArrayList<>();
		List<String> limitations = new ArrayList<>();
		boolean secret = GenericSecretScanAnalyzer.ID.equals(finding.getAnalyzerId());
		if (secret) {
			limitations.add("No source context is sent for a possible secret; only the finding's redacted metadata.");
		}
		else if (finding.getFilePath() != null && finding.getStartLine() != null) {
			int end = finding.getEndLine() == null ? finding.getStartLine() : finding.getEndLine();
			sources.add(new SourceNeed("SOURCE", finding.getFilePath(), Math.max(1, finding.getStartLine() - FINDING_CONTEXT_LINES),
					end + FINDING_CONTEXT_LINES));
		}
		return new Plan(finding.getTitle(), block("TARGET", finding.getFilePath(), finding.getStartLine(),
				finding.getEndLine(), target.toString(), scope), sources, List.of(), List.of(), limitations, false);
	}

	// ── modules ──────────────────────────────────────────────────────────────────────────────────

	private Plan modulePlan(String targetId, Scope scope) {
		if (targetId == null || targetId.isBlank()) {
			throw new BadRequestException("targetId (a module path, e.g. '.') is required for SUMMARIZE_MODULE.");
		}
		String path = targetId.trim();
		List<ModuleRecord> moduleRecords = modules.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId());
		ModuleRecord module = moduleRecords.stream().filter(m -> path.equals(m.getPath())).findFirst().orElse(null);
		List<CodeSymbol> runSymbols = symbols.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId());
		List<CodeSymbol> types = runSymbols.stream()
			.filter(s -> s.getSymbolType().isType() && inModule(s.getModulePath(), path))
			.sorted(Comparator.comparing(CodeSymbol::getFullyQualifiedName))
			.toList();
		if (module == null && types.isEmpty()) {
			throw new ResourceNotFoundException("Module not found in the latest completed analysis of this snapshot.");
		}
		Map<UUID, LayerAssignment> layerOf = new LinkedHashMap<>();
		if (!types.isEmpty()) {
			for (LayerAssignment layer : layers.findBySnapshotIdAndRunIdAndSymbolIdIn(scope.snapshotId(), scope.runId(),
					types.stream().map(CodeSymbol::getId).toList())) {
				layerOf.merge(layer.getSymbol().getId(), layer,
						(a, b) -> a.getConfidence().ordinal() <= b.getConfidence().ordinal() ? a : b);
			}
		}
		List<AnalysisFinding> moduleFindings = findings.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(f -> f.getCategory() != AnalysisCategory.STRUCTURE && inModule(f.getModulePath(), path))
			.toList();
		List<DependencyRecord> moduleDependencies = dependencies.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(d -> !d.isManagementEntry() && inModule(d.getModulePath(), path))
			.sorted(Comparator.comparing((DependencyRecord d) -> String.valueOf(d.getNamespace())).thenComparing(DependencyRecord::getName))
			.toList();

		StringBuilder target = new StringBuilder();
		target.append("module path: ").append(path).append('\n');
		if (module != null) {
			target.append("name: ").append(module.getModuleName()).append('\n');
			if (module.getBuildFilePath() != null) {
				target.append("build file: ").append(module.getBuildFilePath()).append('\n');
			}
			if (module.getEcosystem() != null) {
				target.append("ecosystem: ").append(module.getEcosystem()).append('\n');
			}
			if (module.getDetectedResponsibility() != null) {
				target.append("detected responsibility (parser heuristic): ").append(module.getDetectedResponsibility())
					.append('\n');
			}
		}
		target.append("types: ").append(types.size()).append('\n');
		target.append("direct dependencies: ").append(moduleDependencies.size()).append('\n');
		Map<AnalysisCategory, Long> byCategory = new EnumMap<>(AnalysisCategory.class);
		moduleFindings.forEach(f -> byCategory.merge(f.getCategory(), 1L, Long::sum));
		target.append("findings by category: ").append(byCategory.isEmpty() ? "none" : byCategory.toString()).append('\n');

		List<EvidenceBlock> declarations = new ArrayList<>();
		if (!types.isEmpty()) {
			StringBuilder typeList = new StringBuilder("Types in the module (layer from the architecture analysis):\n");
			types.stream().limit(150).forEach(t -> {
				LayerAssignment layer = layerOf.get(t.getId());
				typeList.append("- ").append(t.getFullyQualifiedName()).append(" (").append(t.getSymbolType())
					.append(layer == null ? "" : ", " + layer.getLayer() + " " + layer.getConfidence()).append(')');
				if (t.getAnnotations() != null && !t.getAnnotations().isEmpty()) {
					typeList.append(' ')
						.append(t.getAnnotations().stream().map(a -> "@" + a.simpleName()).collect(Collectors.joining(" ")));
				}
				typeList.append('\n');
			});
			if (types.size() > 150) {
				typeList.append("- ... ").append(types.size() - 150).append(" more\n");
			}
			declarations.add(block("DECLARATION", null, null, null, typeList.toString(), scope));
			Set<UUID> typeIds = types.stream().map(CodeSymbol::getId).collect(Collectors.toSet());
			List<String> routes = endpoints.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
				.stream()
				.filter(e -> typeIds.contains(e.getController().getId()))
				.sorted(Comparator.comparing(ApiEndpoint::getRoute).thenComparing(ApiEndpoint::getHttpMethod))
				.map(e -> "- " + endpointLine(e) + " in " + simple(e.getController().getFullyQualifiedName()))
				.limit(60)
				.toList();
			if (!routes.isEmpty()) {
				declarations.add(block("DECLARATION", null, null, null,
						"REST endpoints of the module:\n" + String.join("\n", routes), scope));
			}
		}
		if (!moduleDependencies.isEmpty()) {
			declarations.add(block("DECLARATION", null, null, null, "Direct dependencies:\n" + moduleDependencies.stream()
				.limit(60)
				.map(d -> "- " + (d.getNamespace() == null ? "" : d.getNamespace() + ":") + d.getName() + " "
						+ (d.getResolvedVersion() != null ? d.getResolvedVersion()
								: d.getDeclaredVersion() != null ? d.getDeclaredVersion() : "(version not resolved)")
						+ (d.getScope() != null ? " [" + d.getScope() + "]" : ""))
				.collect(Collectors.joining("\n")), scope));
		}
		List<EvidenceBlock> findingBlocks = debtBlocks(moduleFindings, scope, 2);
		List<String> limitations = new ArrayList<>(
				List.of("Module summaries are built from parser facts and findings only; no source code is sent."));
		return new Plan(module != null ? module.getModuleName() : path,
				block("TARGET", module != null ? module.getBuildFilePath() : null, null, null, target.toString(), scope),
				List.of(), findingBlocks, declarations, limitations, false);
	}

	private static boolean inModule(String modulePath, String path) {
		if (modulePath == null) {
			return ".".equals(path);
		}
		return modulePath.equals(path);
	}

	// ── technical debt ───────────────────────────────────────────────────────────────────────────

	private Plan techDebtPlan(Scope scope) {
		List<AnalysisFinding> all = findings.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(f -> f.getCategory() != AnalysisCategory.STRUCTURE)
			.toList();
		StringBuilder target = new StringBuilder();
		target.append("repository: ").append(scope.owner()).append('/').append(scope.repo()).append('\n');
		target.append("commit: ").append(scope.commitSha()).append('\n');
		List<String> techs = technologies.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.map(TechnologyFinding::getTechnology)
			.distinct()
			.sorted()
			.toList();
		if (!techs.isEmpty()) {
			target.append("technologies: ").append(String.join(", ", techs)).append('\n');
		}
		Map<AnalysisCategory, Map<Severity, Long>> counts = new EnumMap<>(AnalysisCategory.class);
		all.forEach(f -> counts.computeIfAbsent(f.getCategory(), c -> new EnumMap<>(Severity.class))
			.merge(f.getSeverity(), 1L, Long::sum));
		target.append("findings: ").append(all.size()).append(" in total\n");
		counts.forEach((category, bySeverity) -> target.append("  ").append(category).append(": ").append(bySeverity)
			.append('\n'));
		long vulnerable = dependencies.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
			.stream()
			.filter(d -> d.getVulnerabilityStatus() != null && d.getVulnerabilityStatus().name().equals("VULNERABLE"))
			.count();
		target.append("dependencies with known vulnerabilities: ").append(vulnerable)
			.append(" (only meaningful when the vulnerability lookup was enabled)\n");
		List<String> limitations = new ArrayList<>(
				List.of("The summary is based on deduplicated findings of deterministic tools; no source code is sent."));
		List<EvidenceBlock> groups = debtBlocks(all, scope, MAX_DEBT_GROUPS / DEBT_GROUPS_PER_BLOCK);
		if (groups.isEmpty()) {
			limitations.add("There are no findings to summarise.");
		}
		return new Plan("Technical debt", block("TARGET", null, null, null, target.toString(), scope), List.of(), groups,
				List.of(), limitations, true);
	}

	/** Findings grouped by category and rule, most severe and most frequent first, in blocks of ten groups. */
	private static List<EvidenceBlock> debtBlocks(List<AnalysisFinding> list, Scope scope, int maxBlocks) {
		Map<String, List<AnalysisFinding>> grouped = new TreeMap<>();
		for (AnalysisFinding f : list) {
			grouped.computeIfAbsent(f.getCategory() + "\u0000" + f.getRuleId(), k -> new ArrayList<>()).add(f);
		}
		List<List<AnalysisFinding>> groups = grouped.values()
			.stream()
			.sorted(Comparator
				.comparing((List<AnalysisFinding> g) -> g.stream().mapToInt(f -> f.getSeverity().ordinal()).min().orElse(9))
				.thenComparing(g -> -g.size())
				.thenComparing(g -> g.get(0).getRuleId()))
			.toList();
		List<EvidenceBlock> blocks = new ArrayList<>();
		for (int from = 0; from < groups.size() && blocks.size() < maxBlocks; from += DEBT_GROUPS_PER_BLOCK) {
			StringBuilder text = new StringBuilder("Deduplicated findings (one line per rule):\n");
			for (List<AnalysisFinding> group : groups.subList(from, Math.min(groups.size(), from + DEBT_GROUPS_PER_BLOCK))) {
				AnalysisFinding first = group.stream()
					.min(Comparator.comparing((AnalysisFinding f) -> f.getSeverity().ordinal())
						.thenComparing(f -> String.valueOf(f.getFilePath()))
						.thenComparing(f -> f.getStartLine() == null ? 0 : f.getStartLine()))
					.orElseThrow();
				String samples = group.stream()
					.filter(f -> f.getFilePath() != null)
					.sorted(Comparator.comparing(AnalysisFinding::getFilePath)
						.thenComparing(f -> f.getStartLine() == null ? 0 : f.getStartLine()))
					.map(f -> f.getFilePath() + (f.getStartLine() != null ? ":" + f.getStartLine() : ""))
					.distinct()
					.limit(3)
					.collect(Collectors.joining(", "));
				text.append("- ").append(first.getCategory()).append(" rule ").append(first.getRuleId()).append(" (")
					.append(first.getAnalyzerId()).append("): ").append(group.size()).append(" finding(s), highest severity ")
					.append(first.getSeverity()).append("; e.g. \"").append(first.getTitle()).append('"');
				if (!samples.isEmpty()) {
					text.append("; at ").append(samples);
				}
				text.append('\n');
			}
			blocks.add(block("FINDINGS", null, null, null, text.toString(), scope));
		}
		return blocks;
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private static EvidenceBlock block(String kind, String path, Integer start, Integer end, String text, Scope scope) {
		return new EvidenceBlock(null, kind, path, start, end, text.strip(), path == null ? null : scope.url(path, start, end),
				false);
	}

	private static String lines(CodeSymbol symbol) {
		if (symbol.getStartLine() == null) {
			return "";
		}
		return symbol.getEndLine() == null || symbol.getEndLine().equals(symbol.getStartLine())
				? " (line " + symbol.getStartLine() + ")"
				: " (lines " + symbol.getStartLine() + "-" + symbol.getEndLine() + ")";
	}

	private static String simple(String fqn) {
		if (fqn == null) {
			return "";
		}
		String type = fqn.contains("#") ? fqn.substring(0, fqn.indexOf('#')) : fqn;
		return type.substring(type.lastIndexOf('.') + 1);
	}

}
