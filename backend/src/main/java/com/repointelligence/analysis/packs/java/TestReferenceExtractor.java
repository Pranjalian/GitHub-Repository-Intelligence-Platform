package com.repointelligence.analysis.packs.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.type.VarType;
import com.repointelligence.analysis.spi.TestReferenceResult;
import com.repointelligence.common.model.Confidence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads which production types and methods a test class names, from its syntax tree alone (nothing is
 * compiled or run). A name is resolved only through the test's own imports and package against the types
 * of the production sources of the same commit; a name that cannot be resolved is dropped, never guessed.
 *
 * <p>
 * A call is recorded when its receiver is a field, parameter or local variable whose declared type is a
 * production type ({@code var x = new T()} counts), or a type name ({@code T.create()}). Calls on
 * chained expressions ({@code mockMvc.perform(...)}) have no known receiver type and are not recorded.
 */
final class TestReferenceExtractor {

	private record Resolved(String fqn, Confidence confidence) {
	}

	private final Set<String> productionTypes;

	TestReferenceExtractor(Set<String> productionTypes) {
		this.productionTypes = productionTypes;
	}

	List<TestReferenceResult> extract(CompilationUnit unit, TypeDeclaration<?> testType, String packageName) {
		Imports imports = new Imports(unit, packageName);
		Map<String, Type> fields = new HashMap<>();
		for (FieldDeclaration field : testType.findAll(FieldDeclaration.class)) {
			for (VariableDeclarator variable : field.getVariables()) {
				fields.putIfAbsent(variable.getNameAsString(), variable.getType());
			}
		}
		Map<MethodDeclaration, Map<String, Type>> localsCache = new IdentityHashMap<>();
		Map<String, TestReferenceResult> found = new LinkedHashMap<>();

		for (ClassOrInterfaceType type : testType.findAll(ClassOrInterfaceType.class)) {
			Resolved resolved = resolve(type.getNameWithScope(), imports);
			if (resolved != null) {
				add(found, method(type), resolved, null, null, "TYPE_USE", line(type), resolved.confidence());
			}
		}
		for (ObjectCreationExpr creation : testType.findAll(ObjectCreationExpr.class)) {
			Resolved resolved = resolve(creation.getType().getNameWithScope(), imports);
			if (resolved != null) {
				add(found, method(creation), resolved, "<init>", creation.getArguments().size(), "INSTANTIATION",
						line(creation), resolved.confidence());
			}
		}
		for (MethodCallExpr call : testType.findAll(MethodCallExpr.class)) {
			Optional<Expression> scope = call.getScope();
			if (scope.isEmpty()) {
				continue;
			}
			MethodDeclaration enclosing = call.findAncestor(MethodDeclaration.class).orElse(null);
			Map<String, Type> locals = enclosing == null ? Map.of()
					: localsCache.computeIfAbsent(enclosing, TestReferenceExtractor::locals);
			Resolved receiver = null;
			String kind = "CALL";
			Expression expression = scope.get();
			if (expression instanceof NameExpr name) {
				String identifier = name.getNameAsString();
				Type declared = locals.containsKey(identifier) ? locals.get(identifier) : fields.get(identifier);
				if (declared != null) {
					receiver = resolve(declared, imports);
				}
				else if (Character.isUpperCase(identifier.charAt(0))) {
					receiver = resolve(identifier, imports);
					kind = "STATIC_CALL";
				}
			}
			else if (expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr
					&& fields.containsKey(access.getNameAsString())) {
				receiver = resolve(fields.get(access.getNameAsString()), imports);
			}
			if (receiver != null) {
				add(found, method(call), receiver, call.getNameAsString(), call.getArguments().size(), kind, line(call),
						receiver.confidence());
			}
		}
		return new ArrayList<>(found.values());
	}

	private static void add(Map<String, TestReferenceResult> found, String testMethod, Resolved type, String member,
			Integer arguments, String kind, int line, Confidence confidence) {
		String key = testMethod + "|" + type.fqn() + "|" + member + "|" + arguments + "|" + kind;
		TestReferenceResult existing = found.get(key);
		if (existing == null || line < existing.line()) {
			found.put(key, new TestReferenceResult(testMethod, type.fqn(), member, arguments, kind, line, confidence));
		}
	}

	private static String method(Node node) {
		return node.findAncestor(MethodDeclaration.class).map(MethodDeclaration::getNameAsString).orElse(null);
	}

	private static int line(Node node) {
		return node.getBegin().map(p -> p.line).orElse(1);
	}

	private Resolved resolve(Type type, Imports imports) {
		if (type instanceof ClassOrInterfaceType named) {
			return resolve(named.getNameWithScope(), imports);
		}
		return null;
	}

	/** {@code Foo}, {@code Outer.Inner} or a fully qualified name, resolved against production types only. */
	private Resolved resolve(String written, Imports imports) {
		if (productionTypes.contains(written)) {
			return new Resolved(written, Confidence.DETECTED);
		}
		int dot = written.indexOf('.');
		String first = dot < 0 ? written : written.substring(0, dot);
		String rest = dot < 0 ? "" : written.substring(dot);
		String single = imports.single.get(first);
		if (single != null && productionTypes.contains(single + rest)) {
			return new Resolved(single + rest, Confidence.DETECTED);
		}
		String samePackage = imports.packageName.isEmpty() ? first : imports.packageName + "." + first;
		if (productionTypes.contains(samePackage + rest)) {
			return new Resolved(samePackage + rest, Confidence.DETECTED);
		}
		List<String> viaWildcard = imports.wildcards.stream()
			.map(prefix -> prefix + "." + first + rest)
			.filter(productionTypes::contains)
			.distinct()
			.toList();
		if (viaWildcard.size() == 1) {
			return new Resolved(viaWildcard.get(0), Confidence.LIKELY);
		}
		return null;
	}

	/** Parameters and locals with a written type (or {@code var x = new T()}). A name declared with two types is dropped. */
	private static Map<String, Type> locals(MethodDeclaration method) {
		Map<String, Type> locals = new HashMap<>();
		Set<String> conflicting = new HashSet<>();
		for (Parameter parameter : method.findAll(Parameter.class)) {
			if (!(parameter.getType() instanceof UnknownType)) {
				put(locals, conflicting, parameter.getNameAsString(), parameter.getType());
			}
		}
		for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
			if (variable.getParentNode().filter(p -> p instanceof FieldDeclaration).isPresent()) {
				continue;
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

	private static final class Imports {

		final String packageName;

		final Map<String, String> single = new HashMap<>();

		final List<String> wildcards = new ArrayList<>();

		Imports(CompilationUnit unit, String packageName) {
			this.packageName = packageName;
			for (ImportDeclaration declaration : unit.getImports()) {
				if (declaration.isStatic()) {
					continue;
				}
				String name = declaration.getNameAsString();
				if (declaration.isAsterisk()) {
					wildcards.add(name);
				}
				else {
					single.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
				}
			}
		}

	}

}
