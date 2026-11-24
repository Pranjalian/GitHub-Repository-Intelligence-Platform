package com.repointelligence.analysis.packs.java;

import com.repointelligence.analysis.spi.ImpactAnalyzer;
import com.repointelligence.analysis.spi.ImpactGraph;
import com.repointelligence.analysis.spi.ImpactGraph.EndpointOf;
import com.repointelligence.analysis.spi.ImpactGraph.EntityLinkOf;
import com.repointelligence.analysis.spi.ImpactGraph.Granularity;
import com.repointelligence.analysis.spi.ImpactGraph.LayerOf;
import com.repointelligence.analysis.spi.ImpactGraph.ModuleLink;
import com.repointelligence.analysis.spi.ImpactGraph.Node;
import com.repointelligence.analysis.spi.ImpactGraph.Reach;
import com.repointelligence.analysis.spi.ImpactGraph.TestReferenceOf;
import com.repointelligence.analysis.spi.ImpactGraph.Traversal;
import com.repointelligence.analysis.spi.ImpactQuery;
import com.repointelligence.analysis.spi.ImpactResult;
import com.repointelligence.analysis.spi.ImpactResult.AffectedComponent;
import com.repointelligence.analysis.spi.ImpactResult.EndpointImpact;
import com.repointelligence.analysis.spi.ImpactResult.ModuleImpact;
import com.repointelligence.analysis.spi.ImpactResult.RelatedData;
import com.repointelligence.analysis.spi.ImpactResult.TestImpact;
import com.repointelligence.common.model.Confidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Impact analysis for Java symbols, built from the stored AST facts (MVP 2 symbols and edges, MVP 5 test
 * references): never from a model. For a method it follows resolved call edges back to the callers, and
 * adds what Java's dispatch rules make possible (a method that overrides a supertype's method may be reached
 * by callers of that supertype's method; a call whose overloads were ambiguous may be this one). For a type
 * it follows every resolved relationship: calls, injection, inheritance and type use.
 *
 * <p>
 * Confidence labels follow the platform's: {@code DETECTED} when every hop is a resolved edge read from the
 * code, {@code LIKELY} for type use only or a naming convention, {@code POSSIBLE} for anything that depends on
 * dynamic dispatch, ambiguous resolution or a test that merely names the type. A path is only as strong as
 * its weakest link.
 */
@Component
public class JavaImpactAnalyzer implements ImpactAnalyzer {

	public static final String ID = "java-impact-analyzer";

	private static final Descriptor DESCRIPTOR = new Descriptor(ID, "Java Impact Analyzer", "1.0.0", Set.of("Java"),
			List.of("The graph comes from static parsing without a classpath: calls on library types, chained "
					+ "expressions, lambdas and static imports have no resolved target and are missing from it.",
					"Reflection, runtime proxies and AOP, runtime wiring (profiles, conditional beans, @Bean factory "
							+ "methods, XML configuration) and generated code are not seen.",
					"Code that the framework invokes (event listeners, schedulers, message handlers) has no caller in "
							+ "the graph, and callers in other repositories or services are not included.",
					"Dynamic dispatch is approximated by method name and parameter count across the project's own "
							+ "supertypes and is always labelled POSSIBLE."));

	private static final int HIERARCHY_DEPTH = 6;

	private static final int MAX_TESTS = 100;

	private static final int MAX_RELATED = 60;

	private static final Set<String> BEHAVIOURAL = Set.of("CALLS", "INJECTS", "EXTENDS", "IMPLEMENTS");

	@Override
	public Descriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public boolean supports(Node target) {
		return target.filePath() != null && target.filePath().endsWith(".java");
	}

	private record Comp(Node node, int depth, UUID via, List<String> relationships, Confidence confidence, String reason) {
	}

	private record Seed(Node node, int baseDepth, Confidence confidence) {
	}

	@Override
	public ImpactResult analyze(ImpactQuery query, ImpactGraph graph) {
		Node target = graph.node(query.targetId())
			.orElseThrow(() -> new IllegalArgumentException("The target symbol is not part of this graph."));
		boolean method = target.isCallable();
		if (!method && !target.isType()) {
			throw new IllegalArgumentException("Impact analysis supports types, methods and constructors, not " + target.type());
		}
		Node owner = method ? target.parentId() == null ? null : graph.node(target.parentId()).orElse(null) : target;
		List<String> limitations = new ArrayList<>();
		Map<UUID, Comp> comps = new LinkedHashMap<>();
		boolean truncated = false;

		// ── seeds: the target, and what dynamic dispatch and ambiguous calls make possible ──
		List<Seed> seeds = new ArrayList<>();
		seeds.add(new Seed(target, 0, Confidence.DETECTED));
		List<Node> supertypes = owner == null ? List.of() : graph.supertypes(owner.id(), HIERARCHY_DEPTH);
		if (method) {
			if (target.type().equals("METHOD")) {
				for (Node supertype : supertypes) {
					for (Node declared : graph.membersOf(supertype.id())) {
						if (declared.type().equals("METHOD") && declared.simpleName().equals(target.simpleName())
								&& sameArity(declared, target)) {
							comps.putIfAbsent(declared.id(), new Comp(declared, 1, target.id(), List.of("OVERRIDES"),
									Confidence.POSSIBLE,
									"Declared in " + simple(supertype.fqn()) + ", which this type extends or implements: callers of "
											+ "that method may reach this one at run time."));
							seeds.add(new Seed(declared, 1, Confidence.POSSIBLE));
						}
					}
				}
			}
			for (ImpactGraph.AmbiguousCaller caller : graph.ambiguousCallers(target)) {
				graph.node(caller.callerId()).ifPresent(node -> {
					comps.putIfAbsent(node.id(), new Comp(node, 1, target.id(), List.of("CALLS"), Confidence.POSSIBLE,
							"Calls a method whose overloads could not be told apart; this one may be the callee"
									+ (caller.file() == null ? "." : " (" + caller.file() + ":" + caller.line() + ").")));
					seeds.add(new Seed(node, 1, Confidence.POSSIBLE));
				});
			}
		}
		else {
			for (Node supertype : supertypes) {
				comps.putIfAbsent(supertype.id(), new Comp(supertype, 1, target.id(),
						List.of(supertype.type().equals("INTERFACE") ? "IMPLEMENTS" : "EXTENDS"), Confidence.POSSIBLE,
						"A supertype of the target: code written against it may run the target at run time."));
				seeds.add(new Seed(supertype, 1, Confidence.POSSIBLE));
			}
		}

		// ── traversal ──
		Granularity granularity = method ? Granularity.METHOD : Granularity.TYPE;
		int totalDependents = 0;
		for (int base : new TreeSet<>(seeds.stream().map(Seed::baseDepth).toList())) {
			List<Seed> group = seeds.stream().filter(s -> s.baseDepth() == base).toList();
			int remaining = query.maxDepth() - base;
			if (remaining < 1) {
				continue;
			}
			Traversal traversal = graph.dependents(group.stream().map(s -> s.node().id()).toList(), granularity, remaining);
			truncated |= traversal.truncated();
			Map<UUID, Confidence> seedConfidence = new HashMap<>();
			group.forEach(s -> seedConfidence.put(s.node().id(), s.confidence()));
			merge(comps, traversal, base, seedConfidence, graph, method, target.id());
		}
		// the target is never its own dependent
		comps.remove(target.id());
		totalDependents = comps.size();

		// ── context of the affected components ──
		List<Comp> ordered = comps.values()
			.stream()
			.sorted(Comparator.comparingInt(Comp::depth)
				.thenComparing(c -> c.confidence().ordinal())
				.thenComparing(c -> qualified(c.node())))
			.toList();
		if (ordered.size() > query.maxComponents()) {
			ordered = ordered.subList(0, query.maxComponents());
			truncated = true;
		}
		Set<UUID> typeIds = new LinkedHashSet<>();
		if (owner != null) {
			typeIds.add(owner.id());
		}
		for (Comp comp : ordered) {
			typeIds.add(comp.node().isType() ? comp.node().id() : comp.node().parentId());
		}
		typeIds.remove(null);
		Map<UUID, LayerOf> layers = graph.layers(typeIds);
		Map<UUID, Comp> shown = new LinkedHashMap<>();
		ordered.forEach(c -> shown.put(c.node().id(), c));

		List<AffectedComponent> components = ordered.stream()
			.map(c -> component(c, layers))
			.toList();
		Map<UUID, Confidence> typeConfidence = new HashMap<>();
		if (owner != null) {
			typeConfidence.put(owner.id(), Confidence.DETECTED);
		}
		for (Comp comp : ordered) {
			UUID type = comp.node().isType() ? comp.node().id() : comp.node().parentId();
			if (type != null) {
				typeConfidence.merge(type, comp.confidence(), JavaImpactAnalyzer::better);
			}
		}

		List<EndpointImpact> endpoints = endpoints(graph, target, owner, method, shown, typeConfidence, layers);
		List<RelatedData> related = related(graph, target, typeIds, typeConfidence);
		List<ModuleImpact> modules = modules(graph, target, ordered, query);
		List<TestImpact> tests = tests(graph, target, owner, method, supertypes, ordered);

		// ── limitations that apply to this result ──
		addLimitations(limitations, graph, target, method, supertypes, ordered, truncated, totalDependents, query);

		int maxDepthReached = ordered.stream().mapToInt(Comp::depth).max().orElse(0);
		LayerOf targetLayer = layers.get(owner == null ? null : owner.id());
		return new ImpactResult(target.id(), method ? "METHOD" : "TYPE", targetLayer == null ? null : targetLayer.layer(),
				components, endpoints, related, modules, tests, limitations, truncated, totalDependents, maxDepthReached);
	}

	// ── components ───────────────────────────────────────────────────────────────────────────────

	/** Adds the reached nodes with the weakest link of their shortest path; an earlier, stronger entry is kept. */
	private void merge(Map<UUID, Comp> comps, Traversal traversal, int base, Map<UUID, Confidence> seedConfidence,
			ImpactGraph graph, boolean method, UUID targetId) {
		Map<UUID, Node> nodes = graph.nodes(traversal.reached().stream().map(Reach::node).distinct().toList());
		Map<UUID, Confidence> reachedConfidence = new HashMap<>(seedConfidence);
		for (Comp existing : comps.values()) {
			reachedConfidence.putIfAbsent(existing.node().id(), existing.confidence());
		}
		List<Reach> byDepth = traversal.reached()
			.stream()
			.sorted(Comparator.comparingInt(Reach::depth).thenComparing(r -> r.node().toString()).thenComparing(r -> r.via().toString()))
			.toList();
		for (Reach reach : byDepth) {
			Node node = nodes.get(reach.node());
			Confidence viaConfidence = reachedConfidence.get(reach.via());
			if (node == null || viaConfidence == null || node.id().equals(targetId)) {
				continue;
			}
			Confidence hop = method || reach.relationships().stream().anyMatch(BEHAVIOURAL::contains) ? Confidence.DETECTED
					: Confidence.LIKELY;
			Confidence confidence = worse(viaConfidence, hop);
			int depth = base + reach.depth();
			Comp existing = comps.get(node.id());
			if (existing != null && (existing.depth() < depth
					|| existing.depth() == depth && existing.confidence().ordinal() <= confidence.ordinal())) {
				continue;
			}
			comps.put(node.id(), new Comp(node, depth, reach.via(), reach.relationships(), confidence,
					reason(node, reach, confidence)));
			reachedConfidence.put(node.id(), confidence);
		}
	}

	private static String reason(Node node, Reach reach, Confidence confidence) {
		String how = String.join(" + ", reach.relationships().stream().map(JavaImpactAnalyzer::verb).toList());
		String where = reach.file() == null ? "" : " (" + reach.file() + (reach.line() == null ? "" : ":" + reach.line()) + ")";
		String suffix = confidence == Confidence.POSSIBLE ? " The path goes through dynamic dispatch or an ambiguous call."
				: confidence == Confidence.LIKELY ? " Type use only: it may not call the changed code." : "";
		return capitalise(how) + " the component one step closer to the target" + where + "." + suffix;
	}

	private static String verb(String relationship) {
		return switch (relationship) {
			case "CALLS" -> "calls";
			case "INJECTS" -> "injects";
			case "EXTENDS" -> "extends";
			case "IMPLEMENTS" -> "implements";
			case "FIELD_TYPE" -> "holds a field of";
			case "PARAM_TYPE" -> "takes as parameter";
			case "RETURN_TYPE" -> "returns";
			default -> relationship.toLowerCase(java.util.Locale.ROOT);
		};
	}

	private static String capitalise(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static AffectedComponent component(Comp comp, Map<UUID, LayerOf> layers) {
		Node node = comp.node();
		UUID typeId = node.isType() ? node.id() : node.parentId();
		LayerOf layer = layers.get(typeId);
		return new AffectedComponent(node.id(), node.isType() ? "TYPE" : "METHOD", name(node), qualified(node),
				layer == null ? null : layer.layer(), comp.depth(), comp.via(), comp.relationships(), comp.confidence(),
				comp.reason(), node.filePath(), node.startLine(), node.endLine(), node.modulePath());
	}

	// ── endpoints, related data, modules ─────────────────────────────────────────────────────────

	private List<EndpointImpact> endpoints(ImpactGraph graph, Node target, Node owner, boolean method, Map<UUID, Comp> shown,
			Map<UUID, Confidence> typeConfidence, Map<UUID, LayerOf> layers) {
		Set<UUID> ids = new LinkedHashSet<>();
		ids.add(target.id());
		shown.keySet().forEach(ids::add);
		Map<UUID, UUID> controllerOfType = new HashMap<>();
		if (owner != null) {
			controllerOfType.put(owner.id(), target.id());
		}
		for (Comp comp : shown.values()) {
			if (comp.node().isType()) {
				controllerOfType.put(comp.node().id(), comp.node().id());
			}
		}
		if (!method) {
			ids.addAll(typeConfidence.keySet());
		}
		List<EndpointOf> found = graph.endpoints(ids);
		if (method) {
			// a controller's endpoint is reached through its handler method only
			found = found.stream().filter(e -> ids.contains(e.handlerId())).toList();
		}
		Map<UUID, Node> nodes = graph.nodes(found.stream()
			.flatMap(e -> java.util.stream.Stream.of(e.controllerId(), e.handlerId()))
			.distinct()
			.toList());
		List<EndpointImpact> result = new ArrayList<>();
		for (EndpointOf endpoint : found) {
			UUID via = method ? endpoint.handlerId() : endpoint.controllerId();
			Comp comp = shown.get(via);
			Confidence confidence = via.equals(target.id()) ? Confidence.DETECTED
					: comp != null ? comp.confidence() : typeConfidence.getOrDefault(endpoint.controllerId(), Confidence.LIKELY);
			Node controller = nodes.get(endpoint.controllerId());
			Node handler = nodes.get(endpoint.handlerId());
			result.add(new EndpointImpact(endpoint.httpMethod(), endpoint.route(), endpoint.routeResolved(),
					endpoint.controllerId(), endpoint.handlerId(), controller == null ? null : qualified(controller),
					handler == null ? null : name(handler), endpoint.filePath(), endpoint.line(), confidence, via));
		}
		result.sort(Comparator.comparing((EndpointImpact e) -> e.confidence().ordinal())
			.thenComparing(EndpointImpact::route)
			.thenComparing(EndpointImpact::httpMethod));
		return result;
	}

	/**
	 * Entities and repositories connected to the affected components: the ones the target (or the affected
	 * types) depends on directly, and the entity a repository manages and the repositories of an entity.
	 */
	private List<RelatedData> related(ImpactGraph graph, Node target, Set<UUID> typeIds, Map<UUID, Confidence> typeConfidence) {
		Map<UUID, ImpactGraph.DependencyOf> used = new LinkedHashMap<>();
		for (ImpactGraph.DependencyOf dependency : graph.directDependencies(List.of(target.id()), target.isType())) {
			used.putIfAbsent(dependency.typeId(), dependency);
		}
		Set<UUID> scope = new LinkedHashSet<>(typeIds);
		scope.addAll(used.keySet());
		if (scope.isEmpty()) {
			return List.of();
		}
		List<EntityLinkOf> links = graph.entityLinks(scope);
		Set<UUID> needed = new LinkedHashSet<>(scope);
		links.forEach(l -> {
			needed.add(l.sourceId());
			needed.add(l.targetId());
		});
		Map<UUID, Node> nodes = graph.nodes(needed);
		Map<UUID, LayerOf> layers = graph.layers(needed);
		Map<UUID, RelatedData> result = new LinkedHashMap<>();
		String targetName = name(target);
		for (ImpactGraph.DependencyOf dependency : used.values()) {
			LayerOf layer = layers.get(dependency.typeId());
			Node node = nodes.get(dependency.typeId());
			if (node != null && layer != null && (layer.layer().equals("REPOSITORY") || layer.layer().equals("ENTITY"))
					&& !typeIds.contains(node.id())) {
				result.put(node.id(), new RelatedData(layer.layer(), node.id(), node.fqn(),
						"used by " + targetName + " (" + verb(dependency.relationship()) + ")", targetName,
						Confidence.DETECTED, node.filePath(), node.startLine()));
			}
		}
		for (EntityLinkOf link : links) {
			boolean sourceIn = scope.contains(link.sourceId());
			boolean targetIn = scope.contains(link.targetId());
			UUID inside = sourceIn ? link.sourceId() : link.targetId();
			UUID other = sourceIn ? link.targetId() : link.sourceId();
			if (sourceIn && targetIn && !link.relationship().equals("REPOSITORY_FOR")) {
				continue; // both sides are in scope anyway
			}
			Node otherNode = nodes.get(other);
			Node insideNode = nodes.get(inside);
			if (otherNode == null || insideNode == null || result.containsKey(other) || typeIds.contains(other)) {
				continue;
			}
			LayerOf layer = layers.get(other);
			String kind = layer != null && layer.layer().equals("REPOSITORY") ? "REPOSITORY" : "ENTITY";
			String relationship = link.relationship().equals("REPOSITORY_FOR")
					? (sourceIn ? "managed by " + simple(insideNode.fqn()) : "manages " + simple(insideNode.fqn()))
					: link.relationship().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
							+ (link.field() == null ? "" : " (field " + link.field() + ")");
			Confidence base = used.containsKey(inside) ? Confidence.DETECTED
					: typeConfidence.getOrDefault(inside, Confidence.POSSIBLE);
			result.put(other, new RelatedData(kind, other, otherNode.fqn(), relationship, simple(insideNode.fqn()), base,
					otherNode.filePath(), otherNode.startLine()));
		}
		return result.values()
			.stream()
			.sorted(Comparator.comparing((RelatedData r) -> r.confidence().ordinal()).thenComparing(RelatedData::qualifiedName))
			.limit(MAX_RELATED)
			.toList();
	}

	private List<ModuleImpact> modules(ImpactGraph graph, Node target, List<Comp> comps, ImpactQuery query) {
		Map<String, int[]> affected = new LinkedHashMap<>();
		Map<String, Confidence> best = new HashMap<>();
		if (target.modulePath() != null) {
			affected.computeIfAbsent(target.modulePath(), k -> new int[1])[0]++;
			best.put(target.modulePath(), Confidence.DETECTED);
		}
		for (Comp comp : comps) {
			String module = comp.node().modulePath();
			if (module != null) {
				affected.computeIfAbsent(module, k -> new int[1])[0]++;
				best.merge(module, comp.confidence(), JavaImpactAnalyzer::better);
			}
		}
		List<ModuleImpact> result = new ArrayList<>();
		affected.forEach((module, count) -> result.add(new ModuleImpact(module, "CONTAINS_AFFECTED", 0, count[0], List.of(),
				best.get(module))));
		for (ModuleLink link : graph.moduleDependents(target.modulePath(), query.maxDepth())) {
			if (!affected.containsKey(link.sourceModule())) {
				result.add(new ModuleImpact(link.sourceModule(), "DEPENDS_ON_TARGET_MODULE", link.depth(), 0,
						link.evidencePaths(), Confidence.POSSIBLE));
			}
		}
		result.sort(Comparator.comparing((ModuleImpact m) -> m.reason().equals("CONTAINS_AFFECTED") ? 0 : 1)
			.thenComparingInt(ModuleImpact::depth)
			.thenComparing(ModuleImpact::modulePath));
		return result;
	}

	// ── tests ────────────────────────────────────────────────────────────────────────────────────

	private static final List<String> RELATION_RANK = List.of("DIRECT_CALL", "NAMING_CONVENTION", "TYPE_REFERENCE",
			"AFFECTED_COMPONENT");

	private record TestAcc(String testSymbol, String filePath, Set<String> methods, String relation, Confidence confidence,
			Integer line, String via) {
	}

	private List<TestImpact> tests(ImpactGraph graph, Node target, Node owner, boolean method, List<Node> supertypes,
			List<Comp> comps) {
		Set<String> typeNames = new LinkedHashSet<>();
		String targetType = owner == null ? null : owner.fqn();
		if (targetType != null) {
			typeNames.add(targetType);
		}
		if (method) {
			supertypes.forEach(s -> typeNames.add(s.fqn()));
		}
		Map<String, Comp> affectedByType = new LinkedHashMap<>();
		for (Comp comp : comps) {
			String type = comp.node().isType() ? comp.node().fqn() : comp.node().ownerName();
			if (!type.equals(targetType)) {
				typeNames.add(type);
				affectedByType.merge(type, comp, (a, b) -> a.depth() <= b.depth() ? a : b);
			}
		}
		if (typeNames.isEmpty()) {
			return List.of();
		}
		Map<String, TestAcc> byTest = new LinkedHashMap<>();
		for (TestReferenceOf ref : graph.testReferences(typeNames)) {
			boolean onTargetType = ref.referencedType().equals(targetType);
			boolean onSupertype = !onTargetType && method && supertypes.stream().anyMatch(s -> s.fqn().equals(ref.referencedType()));
			String relation;
			Confidence confidence;
			String via = null;
			if (onTargetType || onSupertype) {
				boolean namesTarget = method ? callsMethod(ref, target) : ref.referencedMember() != null;
				relation = namesTarget ? "DIRECT_CALL" : "TYPE_REFERENCE";
				confidence = namesTarget ? ref.confidence() : Confidence.POSSIBLE;
				if (onSupertype) {
					confidence = Confidence.POSSIBLE;
					via = simple(ref.referencedType());
					if (!namesTarget) {
						continue; // a test that merely names a supertype says nothing about this method
					}
				}
			}
			else {
				Comp comp = affectedByType.get(ref.referencedType());
				if (comp == null) {
					continue;
				}
				relation = "AFFECTED_COMPONENT";
				boolean callsAffected = ref.referencedMember() != null
						&& (comp.node().isType() || ref.referencedMember().equals(comp.node().simpleName()));
				confidence = callsAffected ? Confidence.LIKELY : Confidence.POSSIBLE;
				via = name(comp.node());
			}
			accumulate(byTest, ref.testSymbol(), ref.testFilePath(), ref.testMethod(), relation, confidence, ref.line(), via);
		}
		for (ImpactGraph.ConventionTest convention : graph.conventionTests(typeNames)) {
			boolean onTarget = convention.productionSymbol().equals(targetType);
			accumulate(byTest, convention.testSymbol(), convention.testFilePath(), null,
					onTarget ? "NAMING_CONVENTION" : "AFFECTED_COMPONENT",
					onTarget ? Confidence.LIKELY : Confidence.POSSIBLE, convention.line(),
					onTarget ? null : simple(convention.productionSymbol()));
		}
		return byTest.values()
			.stream()
			.sorted(Comparator.comparingInt((TestAcc t) -> RELATION_RANK.indexOf(t.relation()))
				.thenComparing(t -> t.confidence().ordinal())
				.thenComparing(TestAcc::testSymbol))
			.limit(MAX_TESTS)
			.map(t -> new TestImpact(t.testSymbol(), t.filePath(), t.methods().stream().sorted().toList(), t.relation(),
					t.confidence(), t.line(), t.via()))
			.toList();
	}

	private static void accumulate(Map<String, TestAcc> byTest, String testSymbol, String file, String method,
			String relation, Confidence confidence, Integer line, String via) {
		TestAcc existing = byTest.get(testSymbol);
		Set<String> methods = new TreeSet<>();
		if (method != null) {
			methods.add(method);
		}
		if (existing == null) {
			byTest.put(testSymbol, new TestAcc(testSymbol, file, methods, relation, confidence, line, via));
			return;
		}
		int existingRank = RELATION_RANK.indexOf(existing.relation());
		int rank = RELATION_RANK.indexOf(relation);
		if (rank < existingRank || rank == existingRank && confidence.ordinal() < existing.confidence().ordinal()) {
			methods.addAll(rank == existingRank ? existing.methods() : Set.of());
			byTest.put(testSymbol, new TestAcc(testSymbol, file, methods, relation, confidence, line, via));
		}
		else if (rank == existingRank) {
			existing.methods().addAll(methods);
		}
	}

	private static boolean callsMethod(TestReferenceOf ref, Node target) {
		if (ref.referencedMember() == null) {
			return false;
		}
		boolean nameMatches = target.type().equals("CONSTRUCTOR") ? ref.referencedMember().equals("<init>")
				: ref.referencedMember().equals(target.simpleName());
		int parameters = target.parameterCount();
		boolean arity = ref.argumentCount() == null || parameters < 0
				|| (target.isVarArgs() ? ref.argumentCount() >= parameters - 1 : ref.argumentCount() == parameters);
		return nameMatches && arity;
	}

	// ── limitations ──────────────────────────────────────────────────────────────────────────────

	private void addLimitations(List<String> limitations, ImpactGraph graph, Node target, boolean method,
			List<Node> supertypes, List<Comp> comps, boolean truncated, int totalDependents, ImpactQuery query) {
		ImpactGraph.GraphStats stats = graph.stats();
		if (comps.stream().anyMatch(c -> c.relationships().contains("OVERRIDES"))) {
			limitations.add("Dynamic dispatch: this method overrides or implements a method of a supertype, so the callers "
					+ "of that supertype method are listed as POSSIBLE; which implementation runs is decided at run time.");
		}
		if (comps.stream().anyMatch(c -> c.reason().startsWith("Calls a method whose overloads"))) {
			limitations.add("Ambiguous calls: some callers use an overload that the parser could not tell apart "
					+ "from this method. They are listed as POSSIBLE.");
		}
		if (!method && !supertypes.isEmpty()) {
			limitations.add("Polymorphism: the target extends or implements project types, so code written against those "
					+ "types is listed as POSSIBLE.");
		}
		if (stats.unresolvedEdges() + stats.ambiguousEdges() > 0) {
			limitations.add((stats.unresolvedEdges() + stats.ambiguousEdges()) + " of " + stats.edges()
					+ " relationships in this snapshot are unresolved or ambiguous (library types, overloads, names that "
					+ "are not project types). Impact follows resolved relationships only, so a dependent reached "
					+ "through one of them is missing.");
		}
		if (stats.callsNotResolvable() > 0) {
			limitations.add(stats.callsNotResolvable() + " calls in this snapshot have a receiver whose type is not known "
					+ "from the source (chained calls, lambdas, static imports, inherited library methods). Any of them "
					+ "might call the target; none is in the graph.");
		}
		if (!stats.testsAnalysed()) {
			limitations.add("Tests were not analysed for this snapshot (the test analyzer did not complete), so no tests are listed.");
		}
		else if (!stats.testReferencesAvailable()) {
			limitations.add("This analysis run was made before test references were recorded: tests are listed only by naming "
					+ "convention. Re-run the analysis (force rescan) to link tests to the code they call.");
		}
		else {
			limitations.add("Tests are linked by the types and methods their source names. Tests that reach the code only "
					+ "through HTTP (MockMvc, TestRestTemplate), reflection or test fixtures in other classes are not linked.");
		}
		if (truncated) {
			limitations.add("The result is capped (" + totalDependents + " dependents found, " + query.maxComponents()
					+ " shown, or the graph query hit its row limit): deeper or further dependents are not listed.");
		}
		limitations.addAll(DESCRIPTOR.limitations());
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private static boolean sameArity(Node a, Node b) {
		int pa = a.parameterCount();
		int pb = b.parameterCount();
		return pa < 0 || pb < 0 || pa == pb;
	}

	private static String simple(String fqn) {
		String type = fqn.contains("#") ? fqn.substring(0, fqn.indexOf('#')) : fqn;
		return type.substring(type.lastIndexOf('.') + 1);
	}

	/** {@code OrderService} for a type, {@code OrderService.place(Order)} for a member. */
	static String name(Node node) {
		if (node.isType()) {
			return simple(node.fqn());
		}
		return simple(node.ownerName()) + "." + (node.signature() != null ? node.signature() : node.simpleName());
	}

	static String qualified(Node node) {
		if (node.isType()) {
			return node.fqn();
		}
		return node.ownerName() + "#" + (node.signature() != null ? node.signature() : node.simpleName());
	}

	private static Confidence worse(Confidence a, Confidence b) {
		return a.ordinal() >= b.ordinal() ? a : b;
	}

	private static Confidence better(Confidence a, Confidence b) {
		return a.ordinal() <= b.ordinal() ? a : b;
	}

}
