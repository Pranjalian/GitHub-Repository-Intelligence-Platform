package com.repointelligence.analysis.packs.java.architecture;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.type.VarType;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.CallableModel;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.FieldModel;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.TypeModel;
import com.repointelligence.analysis.packs.java.architecture.TypeResolver.TypeRef;
import com.repointelligence.analysis.packs.java.architecture.TypeResolver.TypeUse;
import com.repointelligence.analysis.spi.ArchitectureResult.Edge;
import com.repointelligence.analysis.spi.ArchitectureResult.Symbol;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.common.model.Confidence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a {@link JavaProjectModel} into symbols and edges: inheritance, imports, field/parameter/return
 * types, injection and method calls. Every edge carries the file and line it was read from. An edge to a
 * type outside the snapshot keeps its name and is {@code UNRESOLVED}; an edge is never pointed at a
 * guessed target. Edges to JDK types are left out. Repeated identical edges are merged with a count.
 */
public final class SymbolGraphBuilder {

	/** Spring-managed classes: the stereotypes and annotations that make a class a bean. */
	public static final Set<String> BEAN_ANNOTATIONS = Set.of("Component", "Service", "Repository", "Controller",
			"RestController", "Configuration", "ControllerAdvice", "RestControllerAdvice", "SpringBootApplication");

	private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");

	/** Containers whose element type is the injected bean type. */
	private static final Set<String> BEAN_CONTAINERS = Set.of("java.util.List", "java.util.Set",
			"java.util.Collection", "java.lang.Iterable", "java.util.Map", "java.util.Optional", "ObjectProvider",
			"Provider", "org.springframework.beans.factory.ObjectProvider", "jakarta.inject.Provider",
			"javax.inject.Provider");

	/** Where a bean receives one of its dependencies. */
	public record InjectionPoint(TypeModel consumer, TypeRef dependency, String container, String via, String member,
			int line, Confidence confidence, String signal) {

		public boolean isCollection() {
			return container != null && !container.endsWith("Optional") && !container.endsWith("Provider");
		}

	}

	/** Counts that describe how complete the graph is. */
	public record Stats(int resolvedEdges, int unresolvedEdges, int ambiguousEdges, int callsNotResolvable,
			int jdkReferencesSkipped) {
	}

	public record Graph(List<Symbol> symbols, List<Edge> edges, List<InjectionPoint> injections, Stats stats,
			boolean symbolsTruncated, boolean edgesTruncated) {
	}

	private final JavaProjectModel model;

	private final TypeResolver resolver;

	private final int maxSymbols;

	private final int maxEdges;

	private final List<Symbol> symbols = new ArrayList<>();

	private final Set<String> emitted = new HashSet<>();

	private final Map<String, EdgeAcc> edges = new LinkedHashMap<>();

	private final List<InjectionPoint> injections = new ArrayList<>();

	private boolean symbolsTruncated;

	private boolean edgesTruncated;

	private int callsNotResolvable;

	private int jdkSkipped;

	private static final class EdgeAcc {

		final String sourceKey;

		final String targetKey;

		final String targetName;

		final RelationshipType type;

		final EdgeResolution resolution;

		final Map<String, Object> evidence;

		int count = 1;

		EdgeAcc(String sourceKey, String targetKey, String targetName, RelationshipType type, EdgeResolution resolution,
				Map<String, Object> evidence) {
			this.sourceKey = sourceKey;
			this.targetKey = targetKey;
			this.targetName = targetName;
			this.type = type;
			this.resolution = resolution;
			this.evidence = evidence;
		}

	}

	public SymbolGraphBuilder(JavaProjectModel model, TypeResolver resolver, int maxSymbols, int maxEdges) {
		this.model = model;
		this.resolver = resolver;
		this.maxSymbols = maxSymbols;
		this.maxEdges = maxEdges;
	}

	public Graph build(CancellationToken cancellation) {
		// types first, so that a cap on symbols never leaves members without their type
		for (TypeModel type : model.types()) {
			addSymbol(new Symbol(type.key(), type.kind(), type.fqn(), null,
					type.enclosing() != null ? type.enclosing().key() : null, type.filePath(), type.line(), endLine(type.node()),
					Annotations.of(type.node()), Annotations.modifiers(type.node()), type.file().modulePath()));
		}
		for (TypeModel type : model.types()) {
			cancellation.throwIfCancelled();
			addMembers(type);
		}
		for (TypeModel type : model.types()) {
			cancellation.throwIfCancelled();
			addTypeEdges(type);
			addInjection(type);
			for (FieldModel field : type.fields()) {
				addTypeUses(field.key(), field.type(), type, RelationshipType.FIELD_TYPE, field.line());
			}
			for (CallableModel callable : allCallables(type)) {
				if (callable.node() instanceof MethodDeclaration method) {
					addTypeUses(callable.key(), method.getType(), type, RelationshipType.RETURN_TYPE, line(method));
				}
				for (Parameter parameter : callable.node().getParameters()) {
					addTypeUses(callable.key(), parameter.getType(), type, RelationshipType.PARAM_TYPE, line(parameter));
				}
				addCalls(type, callable);
			}
		}
		int resolved = 0;
		int unresolved = 0;
		int ambiguous = 0;
		List<Edge> result = new ArrayList<>(edges.size());
		for (EdgeAcc acc : edges.values()) {
			Map<String, Object> evidence = new LinkedHashMap<>(acc.evidence);
			if (acc.count > 1) {
				evidence.put("occurrences", acc.count);
			}
			result.add(new Edge(acc.sourceKey, acc.targetKey, acc.targetName, acc.type, acc.resolution, evidence));
			switch (acc.resolution) {
				case RESOLVED -> resolved++;
				case UNRESOLVED -> unresolved++;
				case AMBIGUOUS -> ambiguous++;
			}
		}
		return new Graph(List.copyOf(symbols), result, List.copyOf(injections),
				new Stats(resolved, unresolved, ambiguous, callsNotResolvable, jdkSkipped), symbolsTruncated,
				edgesTruncated);
	}

	// ── symbols ──────────────────────────────────────────────────────────────────────────────────

	private void addMembers(TypeModel type) {
		for (FieldModel field : type.fields()) {
			Node node = field.variable() != null ? field.declaration() : field.recordComponent();
			addSymbol(new Symbol(field.key(), SymbolType.FIELD, type.fqn() + "#" + field.name(), field.type().asString(),
					type.key(), type.filePath(), field.line(), endLine(field.variable() != null ? field.variable() : node),
					Annotations.of(node), Annotations.modifiers(node), type.file().modulePath()));
		}
		for (CallableModel callable : allCallables(type)) {
			String signature = (callable.kind() == SymbolType.CONSTRUCTOR ? type.simpleName() : callable.name()) + "("
					+ JavaProjectModel.parameterTypes(callable.node()) + ")";
			addSymbol(new Symbol(callable.key(), callable.kind(), type.fqn() + "#" + callable.name(), signature,
					type.key(), type.filePath(), line(callable.node()), endLine(callable.node()),
					Annotations.of(callable.node()), Annotations.modifiers(callable.node()), type.file().modulePath()));
		}
	}

	private void addSymbol(Symbol symbol) {
		if (symbols.size() >= maxSymbols) {
			symbolsTruncated = true;
			return;
		}
		if (symbol.parentKey() != null && !emitted.contains(symbol.parentKey())) {
			return;
		}
		symbols.add(symbol);
		emitted.add(symbol.key());
	}

	// ── type-level edges ─────────────────────────────────────────────────────────────────────────

	private void addTypeEdges(TypeModel type) {
		if (type.node() instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls) {
			for (ClassOrInterfaceType extended : cls.getExtendedTypes()) {
				addEdge(type.key(), resolveSupertype(type, extended), RelationshipType.EXTENDS, type.filePath(),
						line(extended), Map.of());
			}
			for (ClassOrInterfaceType implemented : cls.getImplementedTypes()) {
				addEdge(type.key(), resolveSupertype(type, implemented), RelationshipType.IMPLEMENTS, type.filePath(),
						line(implemented), Map.of());
			}
		}
		else {
			for (ClassOrInterfaceType implemented : TypeResolver.supertypesWritten(type)) {
				addEdge(type.key(), resolveSupertype(type, implemented), RelationshipType.IMPLEMENTS, type.filePath(),
						line(implemented), Map.of());
			}
		}
		if (type.enclosing() == null && isPrimaryType(type)) {
			for (ImportDeclaration declaration : type.file().unit().getImports()) {
				if (declaration.isStatic() || declaration.isAsterisk()) {
					continue;
				}
				TypeRef ref = resolver.resolve(declaration.getNameAsString(), type, declaration);
				addEdge(type.key(), ref, RelationshipType.IMPORTS, type.filePath(), line(declaration), Map.of());
			}
		}
	}

	private TypeRef resolveSupertype(TypeModel type, ClassOrInterfaceType written) {
		return resolver.resolve(written.getNameWithScope(), type.enclosing() != null ? type.enclosing() : type, written);
	}

	/** Imports belong to the file; they are attached to its first top-level type. */
	private static boolean isPrimaryType(TypeModel type) {
		return type.file().unit().getTypes().getFirst().map(first -> first == type.node()).orElse(false);
	}

	private void addTypeUses(String sourceKey, Type type, TypeModel context, RelationshipType relationship, int line) {
		for (TypeUse use : resolver.uses(type, context)) {
			addEdge(sourceKey, use.ref(), relationship, context.filePath(), line,
					use.typeArgument() ? Map.of("typeArgument", true) : Map.of());
		}
	}

	// ── injection ────────────────────────────────────────────────────────────────────────────────

	private void addInjection(TypeModel type) {
		boolean bean = Annotations.hasAny(type.node(), BEAN_ANNOTATIONS);
		for (FieldModel field : type.fields()) {
			if (field.variable() != null) {
				Annotations.find(field.declaration(), INJECTION_ANNOTATIONS.toArray(String[]::new))
					.ifPresent(annotation -> inject(type, field.type(), "field", field.name(), field.line(),
							Confidence.DETECTED, "@" + Annotations.simpleName(annotation) + " field " + field.name()));
			}
		}
		List<CallableModel> constructors = type.constructors();
		for (CallableModel constructor : constructors) {
			boolean annotated = Annotations.hasAny(constructor.node(), INJECTION_ANNOTATIONS);
			boolean implicit = bean && constructors.size() == 1;
			if (annotated || implicit) {
				String signal = annotated ? "@Autowired constructor" : "single constructor of a Spring bean";
				for (Parameter parameter : constructor.node().getParameters()) {
					inject(type, parameter.getType(), "constructor", parameter.getNameAsString(), line(parameter),
							Confidence.DETECTED, signal + ", parameter " + parameter.getNameAsString());
				}
			}
		}
		if (constructors.isEmpty() && bean) {
			boolean required = Annotations.has(type.node(), "RequiredArgsConstructor");
			boolean all = Annotations.has(type.node(), "AllArgsConstructor");
			if (required || all) {
				String lombok = required ? "@RequiredArgsConstructor" : "@AllArgsConstructor";
				for (FieldModel field : type.fields()) {
					if (field.variable() == null || field.isStatic() || field.isFinal() && field.hasInitializer()) {
						continue;
					}
					if (required && !(field.isFinal() || Annotations.has(field.declaration(), "NonNull"))) {
						continue;
					}
					inject(type, field.type(), "lombok-constructor", field.name(), field.line(), Confidence.LIKELY,
							lombok + " generates a constructor taking " + field.name());
				}
			}
		}
		for (CallableModel method : type.methods()) {
			if (Annotations.hasAny(method.node(), INJECTION_ANNOTATIONS)) {
				for (Parameter parameter : method.node().getParameters()) {
					inject(type, parameter.getType(), "setter", method.name(), line(parameter), Confidence.DETECTED,
							"@Autowired method " + method.name());
				}
			}
		}
	}

	private void inject(TypeModel consumer, Type written, String via, String member, int line, Confidence confidence,
			String signal) {
		Optional<TypeRef> outer = resolver.outer(written, consumer);
		if (outer.isEmpty()) {
			return;
		}
		TypeRef dependency = outer.get();
		String container = null;
		if (!dependency.isProjectType() && isContainer(dependency) && written instanceof ClassOrInterfaceType cls
				&& cls.getTypeArguments().isPresent() && !cls.getTypeArguments().get().isEmpty()) {
			Type element = cls.getTypeArguments().get().getLast().orElseThrow();
			Optional<TypeRef> inner = resolver.outer(element, consumer);
			if (inner.isEmpty()) {
				return;
			}
			container = dependency.name();
			dependency = inner.get();
		}
		if (dependency.jdk()) {
			jdkSkipped++;
			return;
		}
		injections.add(new InjectionPoint(consumer, dependency, container, via, member, line, confidence, signal));
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("via", via);
		evidence.put("member", member);
		evidence.put("confidence", confidence.name());
		evidence.put("signal", signal);
		if (container != null) {
			evidence.put("container", container);
		}
		addEdge(consumer.key(), dependency, RelationshipType.INJECTS, consumer.filePath(), line, evidence);
	}

	private static boolean isContainer(TypeRef ref) {
		return BEAN_CONTAINERS.contains(ref.name()) || BEAN_CONTAINERS.contains(ref.simpleName()) && !ref.isProjectType();
	}

	// ── calls ────────────────────────────────────────────────────────────────────────────────────

	private void addCalls(TypeModel owner, CallableModel callable) {
		Map<String, Type> locals = locals(callable.node());
		for (MethodCallExpr call : callable.node().findAll(MethodCallExpr.class)) {
			Optional<TypeRef> receiver = receiver(owner, call.getScope(), locals);
			if (receiver.isEmpty()) {
				callsNotResolvable++;
				continue;
			}
			TypeRef target = receiver.get();
			if (call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr) {
				Optional<TypeModel> declaring = declaringScope(owner, call.getNameAsString(), call.getArguments().size(),
						call.getScope().isPresent());
				if (declaring.isEmpty()) {
					// a static import or a method inherited from outside the project: the target cannot be named
					callsNotResolvable++;
					continue;
				}
				target = TypeRef.resolved(declaring.get().fqn(), declaring.get());
			}
			addCall(callable, owner, target, call.getNameAsString(), call.getArguments().size(), line(call), false);
		}
		for (ObjectCreationExpr creation : callable.node().findAll(ObjectCreationExpr.class)) {
			if (creation.getAnonymousClassBody().isPresent()) {
				continue;
			}
			TypeRef created = resolver.resolve(creation.getType().getNameWithScope(), owner, creation);
			addCall(callable, owner, created, "<init>", creation.getArguments().size(), line(creation), true);
		}
	}

	private void addCall(CallableModel source, TypeModel owner, TypeRef receiver, String method, int arguments,
			int line, boolean construction) {
		if (receiver.jdk()) {
			jdkSkipped++;
			return;
		}
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("method", method);
		if (construction) {
			evidence.put("instantiation", true);
		}
		if (receiver.isProjectType()) {
			List<CallableModel> candidates = new ArrayList<>();
			if (construction) {
				receiver.target().constructors().stream().filter(c -> c.accepts(arguments)).forEach(candidates::add);
				if (receiver.target().constructors().isEmpty()) {
					// the implicit default constructor has no symbol: the edge points at the type
					addEdge(source.key(), receiver, RelationshipType.CALLS, owner.filePath(), line, evidence);
					return;
				}
			}
			else {
				for (TypeModel type : resolver.hierarchy(receiver.target())) {
					type.methods()
						.stream()
						.filter(m -> m.name().equals(method) && m.accepts(arguments))
						.forEach(candidates::add);
				}
			}
			String name = receiver.target().fqn() + "#" + method;
			if (candidates.size() == 1) {
				addEdge(source.key(), candidates.get(0).key(), name, RelationshipType.CALLS, EdgeResolution.RESOLVED,
						owner.filePath(), line, evidence);
			}
			else if (candidates.size() > 1) {
				evidence.put("candidates", candidates.stream().map(c -> c.owner().fqn() + "#" + c.name() + "("
						+ JavaProjectModel.parameterTypes(c.node()) + ")").distinct().toList());
				addEdge(source.key(), null, name, RelationshipType.CALLS, EdgeResolution.AMBIGUOUS, owner.filePath(), line,
						evidence);
			}
			else {
				evidence.put("note", "not declared in the project type or its project supertypes");
				addEdge(source.key(), null, name, RelationshipType.CALLS, EdgeResolution.UNRESOLVED, owner.filePath(), line,
						evidence);
			}
			return;
		}
		if (receiver.resolution() == EdgeResolution.AMBIGUOUS) {
			evidence.put("candidates", receiver.candidates());
		}
		addEdge(source.key(), null, receiver.name() + "#" + method, RelationshipType.CALLS, receiver.resolution(),
				owner.filePath(), line, evidence);
	}

	/** The innermost type (the owner, then enclosing types unless {@code this} was written) whose hierarchy declares the method. */
	private Optional<TypeModel> declaringScope(TypeModel owner, String name, int arguments, boolean explicitThis) {
		for (TypeModel scope = owner; scope != null; scope = explicitThis ? null : scope.enclosing()) {
			for (TypeModel candidate : resolver.hierarchy(scope)) {
				if (candidate.methods().stream().anyMatch(m -> m.name().equals(name) && m.accepts(arguments))) {
					return Optional.of(scope);
				}
			}
		}
		return Optional.empty();
	}

	/** The declared type of a call's receiver, when it can be known from the source. */
	private Optional<TypeRef> receiver(TypeModel owner, Optional<Expression> scope, Map<String, Type> locals) {
		if (scope.isEmpty() || scope.get() instanceof ThisExpr) {
			return Optional.of(TypeRef.resolved(owner.fqn(), owner));
		}
		Expression expression = scope.get();
		if (expression instanceof SuperExpr) {
			List<TypeModel> supertypes = resolver.projectSupertypes(owner);
			return supertypes.size() == 1 ? Optional.of(TypeRef.resolved(supertypes.get(0).fqn(), supertypes.get(0)))
					: Optional.empty();
		}
		if (expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
			return fieldType(owner, access.getNameAsString());
		}
		if (expression instanceof NameExpr name) {
			String identifier = name.getNameAsString();
			Type local = locals.get(identifier);
			if (local != null) {
				return resolver.outer(local, owner);
			}
			Optional<TypeRef> field = fieldType(owner, identifier);
			if (field.isPresent()) {
				return field;
			}
			if (Character.isUpperCase(identifier.charAt(0))) {
				// a static call on a type name
				return Optional.of(resolver.resolve(identifier, owner, name));
			}
		}
		return Optional.empty();
	}

	private Optional<TypeRef> fieldType(TypeModel owner, String name) {
		for (TypeModel scope = owner; scope != null; scope = scope.enclosing()) {
			for (TypeModel type : resolver.hierarchy(scope)) {
				for (FieldModel field : type.fields()) {
					if (field.name().equals(name)) {
						return resolver.outer(field.type(), type);
					}
				}
			}
		}
		return Optional.empty();
	}

	/** Parameters and local variables with a written type (or {@code var x = new T()}). Names declared twice with different types are dropped. */
	private static Map<String, Type> locals(CallableDeclaration<?> callable) {
		Map<String, Type> locals = new HashMap<>();
		Set<String> conflicting = new HashSet<>();
		for (Parameter parameter : callable.findAll(Parameter.class)) {
			if (!(parameter.getType() instanceof UnknownType)) {
				put(locals, conflicting, parameter.getNameAsString(), parameter.getType());
			}
		}
		for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
			if (variable.getParentNode().filter(p -> p instanceof com.github.javaparser.ast.body.FieldDeclaration).isPresent()) {
				continue; // a field of a local or anonymous class
			}
			Type type = variable.getType();
			if (type instanceof VarType) {
				Optional<Expression> init = variable.getInitializer();
				if (init.isPresent() && init.get() instanceof ObjectCreationExpr creation) {
					type = creation.getType();
				}
				else {
					conflicting.add(variable.getNameAsString());
					continue;
				}
			}
			put(locals, conflicting, variable.getNameAsString(), type);
		}
		conflicting.forEach(locals::remove);
		return locals;
	}

	private static void put(Map<String, Type> locals, Set<String> conflicting, String name, Type type) {
		Type previous = locals.putIfAbsent(name, type);
		if (previous != null && !previous.asString().equals(type.asString())) {
			conflicting.add(name);
		}
	}

	// ── edges ────────────────────────────────────────────────────────────────────────────────────

	private void addEdge(String sourceKey, TypeRef ref, RelationshipType type, String file, int line,
			Map<String, Object> extra) {
		if (ref.jdk()) {
			jdkSkipped++;
			return;
		}
		Map<String, Object> evidence = new LinkedHashMap<>(extra);
		if (ref.resolution() == EdgeResolution.AMBIGUOUS) {
			evidence.put("candidates", ref.candidates());
		}
		String name = ref.resolution() == EdgeResolution.AMBIGUOUS ? ref.writtenName() : ref.name();
		addEdge(sourceKey, ref.isProjectType() ? ref.target().key() : null, name, type, ref.resolution(), file, line,
				evidence);
	}

	private void addEdge(String sourceKey, String targetKey, String targetName, RelationshipType type,
			EdgeResolution resolution, String file, int line, Map<String, Object> extra) {
		if (!emitted.contains(sourceKey) || targetKey != null && !emitted.contains(targetKey)) {
			return; // a symbol beyond the cap
		}
		String dedupe = sourceKey + "|" + type + "|" + (targetKey != null ? targetKey : "?" + targetName);
		EdgeAcc existing = edges.get(dedupe);
		if (existing != null) {
			existing.count++;
			return;
		}
		if (edges.size() >= maxEdges) {
			edgesTruncated = true;
			return;
		}
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("file", file);
		evidence.put("line", line);
		evidence.putAll(extra);
		edges.put(dedupe, new EdgeAcc(sourceKey, targetKey, targetName, type, resolution, evidence));
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private static List<CallableModel> allCallables(TypeModel type) {
		List<CallableModel> all = new ArrayList<>(type.constructors());
		all.addAll(type.methods());
		return all;
	}

	static int line(Node node) {
		return node.getBegin().map(p -> p.line).orElse(1);
	}

	private static Integer endLine(Node node) {
		return node == null ? null : node.getEnd().map(p -> p.line).orElse(null);
	}

}
