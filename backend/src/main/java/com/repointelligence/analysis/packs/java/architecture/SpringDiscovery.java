package com.repointelligence.analysis.packs.java.architecture;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.CallableModel;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.FieldModel;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.TypeModel;
import com.repointelligence.analysis.packs.java.architecture.SymbolGraphBuilder.Graph;
import com.repointelligence.analysis.packs.java.architecture.SymbolGraphBuilder.InjectionPoint;
import com.repointelligence.analysis.packs.java.architecture.TypeResolver.TypeRef;
import com.repointelligence.analysis.spi.ArchitectureResult.Edge;
import com.repointelligence.analysis.spi.ArchitectureResult.Endpoint;
import com.repointelligence.analysis.spi.ArchitectureResult.EntityRelation;
import com.repointelligence.analysis.spi.ArchitectureResult.Layer;
import com.repointelligence.analysis.spi.ArchitectureResult.Pattern;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import com.repointelligence.architecture.domain.LayerMethod;
import com.repointelligence.architecture.domain.PatternType;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/**
 * Spring and JPA architecture discovery on top of the symbol graph: layers, REST endpoints with
 * request/response types, JPA relationships, repository-to-entity mappings, dependency cycles and
 * design-pattern candidates. Rules come from {@code architecture/spring-architecture.yml}. Every
 * statement carries evidence and a confidence; patterns are inferred from structure, never from names.
 */
public final class SpringDiscovery {

	private static final String RULES_RESOURCE = "/architecture/spring-architecture.yml";

	private static final int MAX_CYCLE_FINDINGS = 50;

	private static final int MAX_CONSTANT_DEPTH = 5;

	private static final Map<String, String> MAPPING_METHODS = Map.of("GetMapping", "GET", "PostMapping", "POST",
			"PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "ANY");

	private static final Map<String, EntityRelationshipType> JPA_RELATIONS = Map.of("OneToMany",
			EntityRelationshipType.ONE_TO_MANY, "ManyToOne", EntityRelationshipType.MANY_TO_ONE, "OneToOne",
			EntityRelationshipType.ONE_TO_ONE, "ManyToMany", EntityRelationshipType.MANY_TO_MANY);

	private static final Set<ArchitectureLayer> NOT_ADAPTERS = Set.of(ArchitectureLayer.CONTROLLER,
			ArchitectureLayer.SERVICE, ArchitectureLayer.REPOSITORY, ArchitectureLayer.ENTITY,
			ArchitectureLayer.CONFIGURATION);

	/** The rules file, parsed. */
	public record Rules(Map<String, ArchitectureLayer> annotationLayers, Map<String, Confidence> annotationConfidence,
			Set<String> repositoryBaseTypes, Map<String, ArchitectureLayer> packageSegments, Set<String> responseWrappers) {

		@SuppressWarnings("unchecked")
		public static Rules load() {
			try (InputStream in = SpringDiscovery.class.getResourceAsStream(RULES_RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException("Missing " + RULES_RESOURCE);
				}
				Map<String, Object> root = new Yaml().load(in);
				Map<String, ArchitectureLayer> layers = new LinkedHashMap<>();
				Map<String, Confidence> confidence = new HashMap<>();
				for (Map<String, Object> rule : (List<Map<String, Object>>) root.get("layerAnnotations")) {
					ArchitectureLayer layer = ArchitectureLayer.valueOf((String) rule.get("layer"));
					Confidence level = rule.containsKey("confidence") ? Confidence.valueOf((String) rule.get("confidence"))
							: Confidence.DETECTED;
					for (String annotation : (List<String>) rule.get("annotations")) {
						layers.put(annotation, layer);
						confidence.put(annotation, level);
					}
				}
				Map<String, ArchitectureLayer> segments = new HashMap<>();
				((Map<String, List<String>>) root.get("packageSegments")).forEach(
						(layer, names) -> names.forEach(name -> segments.put(name, ArchitectureLayer.valueOf(layer))));
				return new Rules(layers, confidence, Set.copyOf((List<String>) root.get("repositoryBaseTypes")), segments,
						Set.copyOf((List<String>) root.get("responseWrappers")));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

	}

	public record Discovery(List<Layer> layers, List<Endpoint> endpoints, List<EntityRelation> entityRelations,
			List<Pattern> patterns, List<FindingResult> findings, int unresolvedRoutes, boolean cyclesTruncated) {
	}

	private final JavaProjectModel model;

	private final TypeResolver resolver;

	private final Graph graph;

	private final Rules rules;

	private final Map<String, Layer> layerByType = new LinkedHashMap<>();

	private final Map<String, TypeModel> typesByKey = new HashMap<>();

	private final Map<String, String> ownerOfMember = new HashMap<>();

	private int unresolvedRoutes;

	public SpringDiscovery(JavaProjectModel model, TypeResolver resolver, Graph graph, Rules rules) {
		this.model = model;
		this.resolver = resolver;
		this.graph = graph;
		this.rules = rules;
		for (TypeModel type : model.types()) {
			typesByKey.put(type.key(), type);
			type.fields().forEach(f -> ownerOfMember.put(f.key(), type.key()));
			type.methods().forEach(m -> ownerOfMember.put(m.key(), type.key()));
			type.constructors().forEach(c -> ownerOfMember.put(c.key(), type.key()));
		}
	}

	public Discovery discover() {
		Set<String> emitted = graph.symbols().stream().map(s -> s.key()).collect(Collectors.toSet());
		// 1. layers from annotations and Spring Data hierarchy
		Map<String, TypeRef> repositoryBases = new LinkedHashMap<>();
		for (TypeModel type : model.types()) {
			annotationLayer(type).ifPresent(layer -> layerByType.put(type.key(), layer));
		}
		for (TypeModel type : model.types()) {
			springDataBase(type, new HashSet<>()).ifPresent(base -> {
				repositoryBases.put(type.key(), base);
				Layer existing = layerByType.get(type.key());
				if (existing == null || existing.confidence() != Confidence.DETECTED) {
					layerByType.put(type.key(), new Layer(type.key(), ArchitectureLayer.REPOSITORY, Confidence.DETECTED,
							LayerMethod.TYPE_HIERARCHY, List.of(new Evidence(type.filePath(), type.line(),
									"extends Spring Data " + base.simpleName()))));
				}
			});
		}
		// 2. endpoints, and DTOs from their signatures
		List<Endpoint> endpoints = new ArrayList<>();
		for (TypeModel type : model.types()) {
			if (Annotations.has(type.node(), "RestController", "Controller")) {
				endpoints.addAll(endpoints(type));
			}
		}
		// 3. the package heuristic for whatever is left, and over a merely POSSIBLE @Component
		for (TypeModel type : model.types()) {
			Layer existing = layerByType.get(type.key());
			if (existing == null || existing.confidence() == Confidence.POSSIBLE) {
				packageLayer(type).ifPresent(layer -> {
					List<Evidence> evidence = new ArrayList<>(layer.evidence());
					if (existing != null) {
						evidence.addAll(existing.evidence());
					}
					layerByType.put(type.key(),
							new Layer(type.key(), layer.layer(), layer.confidence(), layer.method(), evidence));
				});
			}
		}
		// 4. entities and repositories
		List<EntityRelation> relations = new ArrayList<>();
		for (TypeModel type : model.types()) {
			if (Annotations.has(type.node(), "Entity", "Embeddable", "MappedSuperclass")) {
				relations.addAll(jpaRelations(type));
			}
		}
		repositoryBases.forEach((key, base) -> repositoryMapping(typesByKey.get(key), base).ifPresent(relations::add));
		// 5. patterns and cycles
		List<Pattern> patterns = new ArrayList<>();
		patterns.addAll(repositoryPatterns(repositoryBases, relations));
		patterns.addAll(strategies());
		patterns.addAll(factories());
		patterns.addAll(adapters());
		List<FindingResult> findings = new ArrayList<>();
		boolean truncated = cycles(findings);

		List<Layer> layers = layerByType.values().stream().filter(l -> emitted.contains(l.symbolKey())).toList();
		return new Discovery(layers, endpoints.stream().filter(e -> emitted.contains(e.handlerKey())).toList(),
				relations.stream().filter(r -> emitted.contains(r.sourceKey())).toList(),
				patterns.stream().filter(p -> emitted.containsAll(p.symbolKeys())).toList(), findings, unresolvedRoutes,
				truncated);
	}

	// ── layers ───────────────────────────────────────────────────────────────────────────────────

	private Optional<Layer> annotationLayer(TypeModel type) {
		Layer best = null;
		if (!(type.node() instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> annotated)) {
			return Optional.empty();
		}
		for (AnnotationExpr annotation : annotated.getAnnotations()) {
			String name = Annotations.simpleName(annotation);
			ArchitectureLayer layer = rules.annotationLayers().get(name);
			if (layer == null) {
				continue;
			}
			Confidence confidence = rules.annotationConfidence().get(name);
			Layer candidate = new Layer(type.key(), layer, confidence, LayerMethod.ANNOTATION,
					List.of(new Evidence(type.filePath(), SymbolGraphBuilder.line(annotation), "@" + name)));
			if (best == null || confidence.ordinal() < best.confidence().ordinal()) {
				best = candidate;
			}
		}
		return Optional.ofNullable(best);
	}

	/** The Spring Data interface this type extends, directly or through project interfaces. */
	private Optional<TypeRef> springDataBase(TypeModel type, Set<String> seen) {
		if (!type.isInterface() || !seen.add(type.key())) {
			return Optional.empty();
		}
		for (ClassOrInterfaceType written : TypeResolver.supertypesWritten(type)) {
			TypeRef ref = resolver.resolve(written.getNameWithScope(), type, written);
			if (!ref.isProjectType() && rules.repositoryBaseTypes().contains(ref.simpleName())
					&& ref.name().startsWith("org.springframework.data.")) {
				return Optional.of(new TypeRef(written.asString(), ref.resolution(), null, ref.name(), List.of(), false));
			}
			if (ref.isProjectType()) {
				Optional<TypeRef> inherited = springDataBase(ref.target(), seen);
				if (inherited.isPresent()) {
					return inherited;
				}
			}
		}
		return Optional.empty();
	}

	private Optional<Layer> packageLayer(TypeModel type) {
		if (type.kind() == com.repointelligence.architecture.domain.SymbolType.ANNOTATION || onlyStaticMembers(type)) {
			return Optional.empty();
		}
		for (String segment : type.packageName().split("\\.")) {
			ArchitectureLayer layer = rules.packageSegments().get(segment.toLowerCase(Locale.ROOT));
			if (layer != null) {
				return Optional.of(new Layer(type.key(), layer, Confidence.LIKELY, LayerMethod.PACKAGE_HEURISTIC,
						List.of(new Evidence(type.filePath(), type.line(), "package " + type.packageName()))));
			}
		}
		return Optional.empty();
	}

	/** Constant holders and utility classes: a package name says nothing about their layer. */
	private static boolean onlyStaticMembers(TypeModel type) {
		boolean anyMember = !type.fields().isEmpty() || !type.methods().isEmpty();
		return anyMember && type.fields().stream().allMatch(FieldModel::isStatic)
				&& type.methods().stream().allMatch(m -> m.node() instanceof MethodDeclaration md && md.isStatic());
	}

	// ── endpoints ────────────────────────────────────────────────────────────────────────────────

	private List<Endpoint> endpoints(TypeModel controller) {
		List<Endpoint> result = new ArrayList<>();
		boolean rest = Annotations.has(controller.node(), "RestController")
				|| Annotations.has(controller.node(), "ResponseBody");
		List<Route> classPaths = Annotations.find(controller.node(), "RequestMapping")
			.map(a -> paths(a, controller))
			.orElse(List.of(new Route("", true)));
		for (CallableModel method : controller.methods()) {
			Optional<AnnotationExpr> mapping = Annotations.find(method.node(), MAPPING_METHODS.keySet().toArray(String[]::new));
			if (mapping.isEmpty() || !(method.node() instanceof MethodDeclaration declaration)) {
				continue;
			}
			AnnotationExpr annotation = mapping.get();
			List<String> httpMethods = httpMethods(annotation);
			List<Route> methodPaths = paths(annotation, controller);
			boolean body = rest || Annotations.has(declaration, "ResponseBody");
			String requestType = null;
			for (Parameter parameter : declaration.getParameters()) {
				if (Annotations.has(parameter, "RequestBody")) {
					requestType = describe(parameter.getType(), controller);
					markDto(parameter.getType(), controller, "request body of " + controller.simpleName() + "."
							+ method.name(), SymbolGraphBuilder.line(parameter));
				}
			}
			String responseType = null;
			if (body && !declaration.getType().isVoidType()) {
				Type unwrapped = unwrap(declaration.getType(), controller);
				responseType = describe(unwrapped, controller);
				markDto(declaration.getType(), controller, "response of " + controller.simpleName() + "." + method.name(),
						SymbolGraphBuilder.line(declaration));
			}
			for (Route base : classPaths) {
				for (Route path : methodPaths) {
					boolean resolved = base.resolved() && path.resolved();
					if (!resolved) {
						unresolvedRoutes++;
					}
					for (String httpMethod : httpMethods) {
						result.add(new Endpoint(httpMethod, join(base.path(), path.path()), resolved, controller.key(),
								method.key(), requestType, responseType, controller.filePath(),
								SymbolGraphBuilder.line(declaration)));
					}
				}
			}
		}
		return result;
	}

	private record Route(String path, boolean resolved) {
	}

	private List<Route> paths(AnnotationExpr annotation, TypeModel owner) {
		Optional<Expression> value = Annotations.member(annotation, "value").or(() -> Annotations.member(annotation, "path"));
		if (value.isEmpty()) {
			return List.of(new Route("", true));
		}
		List<Expression> elements = value.get() instanceof ArrayInitializerExpr array ? array.getValues()
				: List.of(value.get());
		if (elements.isEmpty()) {
			return List.of(new Route("", true));
		}
		List<Route> routes = new ArrayList<>();
		for (Expression element : elements) {
			Optional<String> constant = constant(element, owner, 0);
			routes.add(constant.map(path -> new Route(path, true))
				.orElseGet(() -> new Route("{" + element + "}", false)));
		}
		return routes;
	}

	private static List<String> httpMethods(AnnotationExpr annotation) {
		String name = Annotations.simpleName(annotation);
		if (!"RequestMapping".equals(name)) {
			return List.of(MAPPING_METHODS.get(name));
		}
		Optional<Expression> method = Annotations.member(annotation, "method");
		if (method.isEmpty()) {
			return List.of("ANY");
		}
		List<Expression> elements = method.get() instanceof ArrayInitializerExpr array ? array.getValues()
				: List.of(method.get());
		List<String> result = new ArrayList<>();
		for (Expression element : elements) {
			String text = element.toString();
			result.add(text.substring(text.lastIndexOf('.') + 1));
		}
		return result.isEmpty() ? List.of("ANY") : result;
	}

	/** A compile-time string constant: literals, concatenations and static final fields of project types. */
	private Optional<String> constant(Expression expression, TypeModel owner, int depth) {
		if (depth > MAX_CONSTANT_DEPTH) {
			return Optional.empty();
		}
		if (expression instanceof StringLiteralExpr literal) {
			return Optional.of(literal.asString());
		}
		if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
			Optional<String> left = constant(binary.getLeft(), owner, depth + 1);
			Optional<String> right = constant(binary.getRight(), owner, depth + 1);
			return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
		}
		if (expression instanceof NameExpr name) {
			return constantField(owner, name.getNameAsString(), depth);
		}
		if (expression instanceof FieldAccessExpr access) {
			TypeRef scope = resolver.resolve(access.getScope().toString(), owner, access);
			if (scope.isProjectType()) {
				return constantField(scope.target(), access.getNameAsString(), depth);
			}
		}
		return Optional.empty();
	}

	private Optional<String> constantField(TypeModel owner, String name, int depth) {
		for (TypeModel scope = owner; scope != null; scope = scope.enclosing()) {
			for (TypeModel type : resolver.hierarchy(scope)) {
				for (FieldModel field : type.fields()) {
					if (field.name().equals(name) && field.isStatic() && field.isFinal() && field.hasInitializer()) {
						return constant(field.variable().getInitializer().orElseThrow(), type, depth + 1);
					}
				}
			}
		}
		return Optional.empty();
	}

	static String join(String base, String path) {
		String joined = ("/" + base + "/" + path).replaceAll("/{2,}", "/");
		if (joined.length() > 1 && joined.endsWith("/")) {
			joined = joined.substring(0, joined.length() - 1);
		}
		return joined;
	}

	/** {@code ResponseEntity<List<OrderResponse>>} gives {@code OrderResponse}. */
	private Type unwrap(Type type, TypeModel context) {
		Type current = type;
		while (current instanceof ClassOrInterfaceType cls && cls.getTypeArguments().isPresent()
				&& !cls.getTypeArguments().get().isEmpty()) {
			TypeRef ref = resolver.resolve(cls.getNameWithScope(), context, cls);
			if (ref.isProjectType() || !rules.responseWrappers().contains(ref.simpleName())) {
				break;
			}
			current = cls.getTypeArguments().get().getLast().orElseThrow();
		}
		return current;
	}

	/** The type as written, with the project type's full name when it is one. */
	private String describe(Type type, TypeModel context) {
		Optional<TypeRef> outer = resolver.outer(type, context);
		if (outer.isPresent() && outer.get().isProjectType() && type instanceof ClassOrInterfaceType cls
				&& cls.getTypeArguments().isEmpty()) {
			return outer.get().target().fqn();
		}
		return type.asString();
	}

	private void markDto(Type type, TypeModel context, String signal, int line) {
		Type unwrapped = unwrap(type, context);
		Optional<TypeRef> ref = resolver.outer(unwrapped, context);
		if (ref.isEmpty() || !ref.get().isProjectType()) {
			return;
		}
		TypeModel dto = ref.get().target();
		Layer existing = layerByType.get(dto.key());
		if (existing != null && existing.method() != LayerMethod.API_SIGNATURE) {
			return; // an entity or service returned by a controller keeps its own layer
		}
		List<Evidence> evidence = new ArrayList<>(existing == null ? List.of() : existing.evidence());
		evidence.add(new Evidence(context.filePath(), line, signal));
		layerByType.put(dto.key(), new Layer(dto.key(), ArchitectureLayer.DTO, Confidence.LIKELY, LayerMethod.API_SIGNATURE,
				evidence));
	}

	// ── JPA ──────────────────────────────────────────────────────────────────────────────────────

	private List<EntityRelation> jpaRelations(TypeModel entity) {
		List<EntityRelation> result = new ArrayList<>();
		for (FieldModel field : entity.fields()) {
			if (field.variable() == null) {
				continue;
			}
			FieldDeclaration declaration = (FieldDeclaration) field.declaration();
			for (AnnotationExpr annotation : declaration.getAnnotations()) {
				EntityRelationshipType type = JPA_RELATIONS.get(Annotations.simpleName(annotation));
				if (type == null) {
					continue;
				}
				Map<String, String> attributes = new LinkedHashMap<>(Annotations.attributes(annotation));
				Annotations.find(declaration, "JoinColumn")
					.flatMap(join -> Optional.ofNullable(Annotations.attributes(join).get("name")))
					.ifPresent(name -> attributes.put("joinColumn", name));
				Annotations.find(declaration, "JoinTable")
					.flatMap(join -> Optional.ofNullable(Annotations.attributes(join).get("name")))
					.ifPresent(name -> attributes.put("joinTable", name));
				TypeRef target = Annotations.member(annotation, "targetEntity")
					.filter(ClassExpr.class::isInstance)
					.map(e -> resolver.resolve(((ClassExpr) e).getType().asString(), entity, e))
					.orElseGet(() -> elementType(field.type(), entity));
				Map<String, Object> evidence = Map.of("file", entity.filePath(), "line", field.line(), "annotation",
						"@" + Annotations.simpleName(annotation));
				result.add(new EntityRelation(entity.key(), target.isProjectType() ? target.target().key() : null,
						target.isProjectType() ? target.target().fqn() : target.name(), type, field.name(), attributes,
						evidence));
			}
		}
		return result;
	}

	/** {@code List<Pet>} and {@code Map<String, Pet>} give {@code Pet}; a plain type gives itself. */
	private TypeRef elementType(Type type, TypeModel context) {
		if (type instanceof ClassOrInterfaceType cls && cls.getTypeArguments().isPresent()
				&& !cls.getTypeArguments().get().isEmpty()) {
			TypeRef outer = resolver.resolve(cls.getNameWithScope(), context, cls);
			if (!outer.isProjectType()) {
				Type element = cls.getTypeArguments().get().getLast().orElseThrow();
				return resolver.outer(element, context).orElse(outer);
			}
		}
		return resolver.outer(type, context).orElseThrow();
	}

	private Optional<EntityRelation> repositoryMapping(TypeModel repository, TypeRef base) {
		for (ClassOrInterfaceType written : TypeResolver.supertypesWritten(repository)) {
			TypeRef ref = resolver.resolve(written.getNameWithScope(), repository, written);
			if (!ref.name().equals(base.name()) || written.getTypeArguments().isEmpty()
					|| written.getTypeArguments().get().isEmpty()) {
				continue;
			}
			Type entityType = written.getTypeArguments().get().get(0);
			TypeRef entity = resolver.outer(entityType, repository).orElse(null);
			if (entity == null || entity.jdk()) {
				return Optional.empty();
			}
			Map<String, String> attributes = new LinkedHashMap<>();
			attributes.put("base", base.simpleName());
			if (written.getTypeArguments().get().size() > 1) {
				attributes.put("idType", written.getTypeArguments().get().get(1).asString());
			}
			return Optional.of(new EntityRelation(repository.key(), entity.isProjectType() ? entity.target().key() : null,
					entity.isProjectType() ? entity.target().fqn() : entity.name(), EntityRelationshipType.REPOSITORY_FOR,
					null, attributes, Map.of("file", repository.filePath(), "line", SymbolGraphBuilder.line(written),
							"signal", "generic argument of " + written.asString())));
		}
		return Optional.empty();
	}

	// ── patterns ─────────────────────────────────────────────────────────────────────────────────

	private List<Pattern> repositoryPatterns(Map<String, TypeRef> bases, List<EntityRelation> relations) {
		List<Pattern> result = new ArrayList<>();
		bases.forEach((key, base) -> {
			List<String> keys = new ArrayList<>(List.of(key));
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("summary", typesByKey.get(key).simpleName() + " extends Spring Data " + base.simpleName());
			evidence.put("file", typesByKey.get(key).filePath());
			relations.stream()
				.filter(r -> r.type() == EntityRelationshipType.REPOSITORY_FOR && r.sourceKey().equals(key))
				.findFirst()
				.ifPresent(r -> {
					evidence.put("entity", r.targetName());
					if (r.targetKey() != null) {
						keys.add(r.targetKey());
					}
				});
			result.add(new Pattern(PatternType.REPOSITORY, keys, Confidence.DETECTED, evidence));
		});
		return result;
	}

	/** An interface with at least two project implementations that is injected as a collection or map. */
	private List<Pattern> strategies() {
		Map<String, List<InjectionPoint>> byInterface = new LinkedHashMap<>();
		for (InjectionPoint injection : graph.injections()) {
			if (injection.isCollection() && injection.dependency().isProjectType()
					&& (injection.dependency().target().isInterface() || injection.dependency().target().isAbstract())) {
				byInterface.computeIfAbsent(injection.dependency().target().key(), k -> new ArrayList<>()).add(injection);
			}
		}
		List<Pattern> result = new ArrayList<>();
		byInterface.forEach((key, injections) -> {
			TypeModel strategy = typesByKey.get(key);
			List<TypeModel> implementations = implementationsOf(strategy);
			if (implementations.size() < 2) {
				return;
			}
			List<String> keys = new ArrayList<>(List.of(key));
			implementations.forEach(i -> keys.add(i.key()));
			injections.stream().map(i -> i.consumer().key()).distinct().forEach(keys::add);
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("summary", strategy.simpleName() + " has " + implementations.size()
					+ " implementations injected together as a " + injections.get(0).container());
			evidence.put("interface", strategy.fqn());
			evidence.put("implementations", implementations.stream().map(TypeModel::fqn).toList());
			evidence.put("injectedInto", injections.stream()
				.map(i -> i.consumer().fqn() + " (" + i.via() + " " + i.member() + ", " + i.consumer().filePath() + ":"
						+ i.line() + ")")
				.distinct()
				.toList());
			result.add(new Pattern(PatternType.STRATEGY, keys, Confidence.LIKELY, evidence));
		});
		return result;
	}

	/** A method returning an abstraction that creates different project implementations in branches. */
	private List<Pattern> factories() {
		List<Pattern> result = new ArrayList<>();
		for (TypeModel type : model.types()) {
			for (CallableModel method : type.methods()) {
				if (!(method.node() instanceof MethodDeclaration declaration) || declaration.getBody().isEmpty()) {
					continue;
				}
				Optional<TypeRef> returned = resolver.outer(declaration.getType(), type);
				if (returned.isEmpty() || !returned.get().isProjectType()) {
					continue;
				}
				TypeModel product = returned.get().target();
				if (!product.isInterface() && !product.isAbstract()) {
					continue;
				}
				Map<String, TypeModel> created = new LinkedHashMap<>();
				for (ObjectCreationExpr creation : declaration.findAll(ObjectCreationExpr.class)) {
					if (!insideBranch(creation, declaration)) {
						continue;
					}
					TypeRef ref = resolver.resolve(creation.getType().getNameWithScope(), type, creation);
					if (ref.isProjectType() && ref.target() != product
							&& resolver.hierarchy(ref.target()).contains(product)) {
						created.putIfAbsent(ref.target().key(), ref.target());
					}
				}
				if (created.size() < 2) {
					continue;
				}
				List<String> keys = new ArrayList<>(List.of(type.key(), method.key(), product.key()));
				keys.addAll(created.keySet());
				Map<String, Object> evidence = new LinkedHashMap<>();
				evidence.put("summary", type.simpleName() + "." + method.name() + " returns " + product.simpleName()
						+ " and creates " + created.size() + " different implementations depending on a branch");
				evidence.put("method", type.fqn() + "#" + method.name());
				evidence.put("returns", product.fqn());
				evidence.put("creates", created.values().stream().map(TypeModel::fqn).toList());
				evidence.put("file", type.filePath());
				evidence.put("line", SymbolGraphBuilder.line(declaration));
				result.add(new Pattern(PatternType.FACTORY, keys, Confidence.POSSIBLE, evidence));
			}
		}
		return result;
	}

	private static boolean insideBranch(Node node, Node stop) {
		Node current = node.getParentNode().orElse(null);
		while (current != null && current != stop) {
			if (current instanceof SwitchStmt || current instanceof SwitchExpr || current instanceof IfStmt
					|| current instanceof ConditionalExpr) {
				return true;
			}
			current = current.getParentNode().orElse(null);
		}
		return false;
	}

	/** A class implementing an interface whose methods mostly delegate to a wrapped object of another type. */
	private List<Pattern> adapters() {
		List<Pattern> result = new ArrayList<>();
		for (TypeModel type : model.types()) {
			if (type.isInterface() || type.isAbstract() || type.kind() != com.repointelligence.architecture.domain.SymbolType.CLASS) {
				continue;
			}
			Layer layer = layerByType.get(type.key());
			if (layer != null && NOT_ADAPTERS.contains(layer.layer()) && layer.confidence() == Confidence.DETECTED) {
				continue;
			}
			List<ClassOrInterfaceType> interfaces = type.node() instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls
					? cls.getImplementedTypes() : List.of();
			if (interfaces.isEmpty()) {
				continue;
			}
			ClassOrInterfaceType target = interfaces.get(0);
			TypeRef targetRef = resolver.resolve(target.getNameWithScope(), type, target);
			if (targetRef.jdk()) {
				continue;
			}
			List<CallableModel> implemented = implementedMethods(type, targetRef);
			if (implemented.isEmpty()) {
				continue;
			}
			for (FieldModel field : type.fields()) {
				if (field.isStatic()) {
					continue;
				}
				Optional<TypeRef> adaptee = resolver.outer(field.type(), type);
				if (adaptee.isEmpty() || adaptee.get().jdk() || adaptee.get().name().equals(targetRef.name())
						|| adaptee.get().isProjectType() && targetRef.isProjectType()
								&& resolver.hierarchy(adaptee.get().target()).contains(targetRef.target())) {
					continue;
				}
				List<String> delegating = implemented.stream()
					.filter(m -> delegatesTo(m, field.name()))
					.map(CallableModel::name)
					.toList();
				if (delegating.size() * 3 < implemented.size() * 2) {
					continue;
				}
				List<String> keys = new ArrayList<>(List.of(type.key()));
				if (targetRef.isProjectType()) {
					keys.add(targetRef.target().key());
				}
				if (adaptee.get().isProjectType()) {
					keys.add(adaptee.get().target().key());
				}
				Map<String, Object> evidence = new LinkedHashMap<>();
				evidence.put("summary", type.simpleName() + " implements " + targetRef.simpleName() + " by delegating "
						+ delegating.size() + " of " + implemented.size() + " methods to its " + adaptee.get().simpleName()
						+ " field '" + field.name() + "'");
				evidence.put("interface", targetRef.name());
				evidence.put("adaptee", adaptee.get().name());
				evidence.put("delegatingMethods", delegating);
				evidence.put("file", type.filePath());
				evidence.put("line", type.line());
				result.add(new Pattern(PatternType.ADAPTER, keys, Confidence.POSSIBLE, evidence));
				break;
			}
		}
		return result;
	}

	/** Methods of the class that implement the interface: by name and arity for project interfaces, else {@code @Override}. */
	private List<CallableModel> implementedMethods(TypeModel type, TypeRef target) {
		if (target.isProjectType()) {
			Set<String> declared = new HashSet<>();
			for (TypeModel t : resolver.hierarchy(target.target())) {
				t.methods().forEach(m -> declared.add(m.name() + "/" + m.arity()));
			}
			return type.methods().stream().filter(m -> declared.contains(m.name() + "/" + m.arity())).toList();
		}
		return type.methods().stream().filter(m -> Annotations.has(m.node(), "Override")).toList();
	}

	private static boolean delegatesTo(CallableModel method, String field) {
		return method.node().findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getScope().map(scope -> {
			if (scope instanceof NameExpr name) {
				return name.getNameAsString().equals(field);
			}
			return scope instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr
					&& access.getNameAsString().equals(field);
		}).orElse(false));
	}

	private List<TypeModel> implementationsOf(TypeModel abstraction) {
		List<TypeModel> result = new ArrayList<>();
		for (TypeModel type : model.types()) {
			if (type != abstraction && !type.isInterface() && !type.isAbstract()
					&& resolver.hierarchy(type).contains(abstraction)) {
				result.add(type);
			}
		}
		return result;
	}

	// ── cycles ───────────────────────────────────────────────────────────────────────────────────

	/** @return whether findings were capped */
	private boolean cycles(List<FindingResult> findings) {
		Map<String, Map<String, Edge>> injection = new LinkedHashMap<>();
		Map<String, Map<String, Edge>> types = new LinkedHashMap<>();
		for (Edge edge : graph.edges()) {
			if (edge.resolution() != EdgeResolution.RESOLVED || edge.type() == RelationshipType.IMPORTS) {
				continue;
			}
			String from = typeOf(edge.sourceKey());
			String to = typeOf(edge.targetKey());
			if (from == null || to == null) {
				continue;
			}
			if (edge.type() == RelationshipType.INJECTS) {
				injection.computeIfAbsent(from, k -> new LinkedHashMap<>()).putIfAbsent(to, edge);
			}
			if (!from.equals(to) && !nested(from, to)) {
				types.computeIfAbsent(from, k -> new LinkedHashMap<>()).putIfAbsent(to, edge);
			}
		}
		Set<Set<String>> reported = new HashSet<>();
		int count = 0;
		boolean truncated = false;
		for (List<String> component : Tarjan.components(injection)) {
			if (component.size() < 2 && !selfLoop(injection, component)) {
				continue;
			}
			if (count++ >= MAX_CYCLE_FINDINGS) {
				truncated = true;
				break;
			}
			reported.add(new HashSet<>(component));
			findings.add(cycleFinding("injection-cycle", Severity.MEDIUM, "Circular dependency injection between ",
					"These beans depend on each other through injection. Spring Boot refuses such cycles at startup by "
							+ "default (spring.main.allow-circular-references=false), and they make the classes hard to change "
							+ "and test separately.",
					"Break the cycle: move the shared logic into a third bean, or depend on an event or interface instead.",
					component, injection));
		}
		for (List<String> component : Tarjan.components(types)) {
			if (component.size() < 2 || reported.contains(new HashSet<>(component)) || allEntities(component)) {
				continue;
			}
			if (count++ >= MAX_CYCLE_FINDINGS) {
				truncated = true;
				break;
			}
			findings.add(cycleFinding("type-cycle", Severity.LOW, "Cyclic type dependency between ",
					"These types refer to each other (fields, parameters, return types, calls or injection), so none of "
							+ "them can be changed, reused or tested without the others.",
					"Consider an interface or moving shared types so the dependencies point one way.", component, types));
		}
		return truncated;
	}

	private FindingResult cycleFinding(String rule, Severity severity, String titlePrefix, String description,
			String nextStep, List<String> component, Map<String, Map<String, Edge>> graphEdges) {
		List<TypeModel> members = component.stream().map(typesByKey::get).toList();
		List<Map<String, Object>> edges = new ArrayList<>();
		Set<String> inCycle = new LinkedHashSet<>(component);
		for (String from : component) {
			graphEdges.getOrDefault(from, Map.of()).forEach((to, edge) -> {
				if (inCycle.contains(to)) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("from", typesByKey.get(from).fqn());
					item.put("to", typesByKey.get(to).fqn());
					item.put("relationship", edge.type().name());
					item.put("file", edge.evidence().get("file"));
					item.put("line", edge.evidence().get("line"));
					edges.add(item);
				}
			});
		}
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("members", members.stream().map(TypeModel::fqn).toList());
		evidence.put("edges", edges);
		String names = members.stream().map(TypeModel::simpleName).limit(5).collect(Collectors.joining(", "))
				+ (members.size() > 5 ? " and " + (members.size() - 5) + " more" : "");
		TypeModel first = members.get(0);
		return new FindingResult(AnalysisCategory.ARCHITECTURE, rule, severity, Confidence.DETECTED, titlePrefix + names,
				description, first.filePath(), first.file().modulePath(), first.line(), null, evidence,
				AnalysisMethod.GRAPH_INFERENCE, nextStep);
	}

	private static boolean selfLoop(Map<String, Map<String, Edge>> edges, List<String> component) {
		String only = component.get(0);
		return edges.getOrDefault(only, Map.of()).containsKey(only);
	}

	private boolean allEntities(List<String> component) {
		return component.stream()
			.allMatch(key -> layerByType.containsKey(key) && layerByType.get(key).layer() == ArchitectureLayer.ENTITY);
	}

	private boolean nested(String a, String b) {
		return enclosedBy(typesByKey.get(a), b) || enclosedBy(typesByKey.get(b), a);
	}

	private static boolean enclosedBy(TypeModel type, String key) {
		for (TypeModel scope = type == null ? null : type.enclosing(); scope != null; scope = scope.enclosing()) {
			if (scope.key().equals(key)) {
				return true;
			}
		}
		return false;
	}

	private String typeOf(String symbolKey) {
		if (symbolKey == null) {
			return null;
		}
		return typesByKey.containsKey(symbolKey) ? symbolKey : ownerOfMember.get(symbolKey);
	}

	/** Strongly connected components (Tarjan), iterative so deep graphs cannot overflow the stack. */
	static final class Tarjan {

		private Tarjan() {
		}

		static List<List<String>> components(Map<String, Map<String, Edge>> graph) {
			Set<String> nodes = new LinkedHashSet<>(graph.keySet());
			graph.values().forEach(targets -> nodes.addAll(targets.keySet()));
			Map<String, Integer> index = new HashMap<>();
			Map<String, Integer> low = new HashMap<>();
			Set<String> onStack = new HashSet<>();
			List<String> stack = new ArrayList<>();
			List<List<String>> result = new ArrayList<>();
			int[] counter = { 0 };
			for (String start : nodes) {
				if (index.containsKey(start)) {
					continue;
				}
				// frames: node and an iterator over its successors
				List<Object[]> frames = new ArrayList<>();
				frames.add(new Object[] { start, new ArrayList<>(graph.getOrDefault(start, Map.of()).keySet()).iterator() });
				index.put(start, counter[0]);
				low.put(start, counter[0]++);
				stack.add(start);
				onStack.add(start);
				while (!frames.isEmpty()) {
					Object[] frame = frames.get(frames.size() - 1);
					String node = (String) frame[0];
					@SuppressWarnings("unchecked")
					java.util.Iterator<String> successors = (java.util.Iterator<String>) frame[1];
					if (successors.hasNext()) {
						String next = successors.next();
						if (!index.containsKey(next)) {
							index.put(next, counter[0]);
							low.put(next, counter[0]++);
							stack.add(next);
							onStack.add(next);
							frames.add(new Object[] { next,
									new ArrayList<>(graph.getOrDefault(next, Map.of()).keySet()).iterator() });
						}
						else if (onStack.contains(next)) {
							low.put(node, Math.min(low.get(node), index.get(next)));
						}
						continue;
					}
					frames.remove(frames.size() - 1);
					if (!frames.isEmpty()) {
						String parent = (String) frames.get(frames.size() - 1)[0];
						low.put(parent, Math.min(low.get(parent), low.get(node)));
					}
					if (low.get(node).equals(index.get(node))) {
						List<String> component = new ArrayList<>();
						String member;
						do {
							member = stack.remove(stack.size() - 1);
							onStack.remove(member);
							component.add(member);
						}
						while (!member.equals(node));
						java.util.Collections.reverse(component);
						result.add(component);
					}
				}
			}
			return result;
		}

	}

}
