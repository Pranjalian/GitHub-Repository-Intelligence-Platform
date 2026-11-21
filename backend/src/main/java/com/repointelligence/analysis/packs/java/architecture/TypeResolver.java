package com.repointelligence.analysis.packs.java.architecture;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.ast.type.WildcardType;
import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel.TypeModel;
import com.repointelligence.architecture.domain.EdgeResolution;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves type names to project types using only the source: the file's package, its imports, nested
 * types and the project index. There is no classpath, so a type that is not in the snapshot is never
 * resolved: it keeps the imported (or written) name and is {@code UNRESOLVED}. Several matching project
 * types give {@code AMBIGUOUS}. JDK types ({@code java.*}, primitives) are recognised so that they can be
 * left out of the graph; they are looked up by name only, nothing from the repository is ever loaded.
 */
public final class TypeResolver {

	private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
			"double", "void");

	private static final Map<String, Boolean> JDK_CACHE = new ConcurrentHashMap<>();

	/** The outcome of resolving one type name. {@code jdk}: a JDK type or a type variable, left out of the graph. */
	public record TypeRef(String writtenName, EdgeResolution resolution, TypeModel target, String name,
			List<String> candidates, boolean jdk) {

		static TypeRef resolved(String written, TypeModel target) {
			return new TypeRef(written, EdgeResolution.RESOLVED, target, target.fqn(), List.of(), false);
		}

		static TypeRef unresolved(String written, String name) {
			return new TypeRef(written, EdgeResolution.UNRESOLVED, null, name, List.of(), false);
		}

		static TypeRef ambiguous(String written, List<String> candidates) {
			return new TypeRef(written, EdgeResolution.AMBIGUOUS, null, written, List.copyOf(candidates), false);
		}

		static TypeRef jdk(String written, String name) {
			return new TypeRef(written, EdgeResolution.UNRESOLVED, null, name, List.of(), true);
		}

		public boolean isProjectType() {
			return target != null;
		}

		public String simpleName() {
			int dot = name.lastIndexOf('.');
			return dot >= 0 ? name.substring(dot + 1) : name;
		}

	}

	/** A type use with its place in the written type: {@code List<Owner>} gives {@code List} then {@code Owner}. */
	public record TypeUse(TypeRef ref, boolean typeArgument) {
	}

	private final JavaProjectModel model;

	private final Map<String, List<TypeModel>> supertypeCache = new java.util.HashMap<>();

	public TypeResolver(JavaProjectModel model) {
		this.model = model;
	}

	/** Resolves a type name written inside {@code context} (the innermost enclosing type). */
	public TypeRef resolve(String written, TypeModel context, Node usage) {
		return resolve(written, context, usage, true);
	}

	/**
	 * @param searchInherited whether nested types inherited from project supertypes are considered; off
	 * while resolving the supertypes themselves, which would otherwise recurse
	 */
	private TypeRef resolve(String written, TypeModel context, Node usage, boolean searchInherited) {
		String name = stripGenerics(written);
		if (PRIMITIVES.contains(name) || name.equals("var")) {
			return TypeRef.jdk(written, name);
		}
		if (isTypeVariable(name, context, usage)) {
			return TypeRef.jdk(written, name);
		}
		int dot = name.indexOf('.');
		if (dot > 0) {
			return resolveQualified(written, name, context, searchInherited);
		}
		return resolveSimple(written, name, context, searchInherited);
	}

	/** Every type named in a written type: the type itself first, then type arguments, array components and bounds. */
	public List<TypeUse> uses(Type type, TypeModel context) {
		List<TypeUse> uses = new ArrayList<>();
		collect(type, context, false, uses);
		return uses;
	}

	/** The outer type of a written type (the component type for arrays), resolved. */
	public Optional<TypeRef> outer(Type type, TypeModel context) {
		Type t = type;
		while (t instanceof ArrayType array) {
			t = array.getComponentType();
		}
		if (t instanceof ClassOrInterfaceType cls) {
			return Optional.of(resolve(cls.getNameWithScope(), context, cls));
		}
		if (t.isPrimitiveType() || t.isVoidType()) {
			return Optional.of(TypeRef.jdk(t.asString(), t.asString()));
		}
		return Optional.empty();
	}

	/** The project supertypes of a type (extends and implements that resolve to project types). */
	public List<TypeModel> projectSupertypes(TypeModel type) {
		List<TypeModel> cached = supertypeCache.get(type.key());
		if (cached != null) {
			return cached;
		}
		List<TypeModel> result = new ArrayList<>();
		for (ClassOrInterfaceType written : supertypesWritten(type)) {
			TypeRef ref = resolve(written.getNameWithScope(), type.enclosing() != null ? type.enclosing() : type, written,
					false);
			if (ref.isProjectType()) {
				result.add(ref.target());
			}
		}
		supertypeCache.put(type.key(), List.copyOf(result));
		return result;
	}

	/** The type and all its project supertypes, transitively, without repeats. */
	public List<TypeModel> hierarchy(TypeModel type) {
		List<TypeModel> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		List<TypeModel> queue = new ArrayList<>(List.of(type));
		while (!queue.isEmpty()) {
			TypeModel next = queue.remove(0);
			if (seen.add(next.key())) {
				result.add(next);
				queue.addAll(projectSupertypes(next));
			}
		}
		return result;
	}

	/** The extends and implements clauses as written. */
	public static List<ClassOrInterfaceType> supertypesWritten(TypeModel type) {
		List<ClassOrInterfaceType> result = new ArrayList<>();
		if (type.node() instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls) {
			result.addAll(cls.getExtendedTypes());
			result.addAll(cls.getImplementedTypes());
		}
		else if (type.node() instanceof com.github.javaparser.ast.body.EnumDeclaration en) {
			result.addAll(en.getImplementedTypes());
		}
		else if (type.node() instanceof com.github.javaparser.ast.body.RecordDeclaration record) {
			result.addAll(record.getImplementedTypes());
		}
		return result;
	}

	/** Whether a name is a JDK type: {@code java.*} looked up by name in the platform class loader, never initialised. */
	public static boolean isJdkType(String fqn) {
		if (!fqn.startsWith("java.")) {
			return false;
		}
		return JDK_CACHE.computeIfAbsent(fqn, name -> {
			try {
				Class.forName(name, false, ClassLoader.getPlatformClassLoader());
				return true;
			}
			catch (ClassNotFoundException | LinkageError ex) {
				return false;
			}
		});
	}

	// ── resolution ───────────────────────────────────────────────────────────────────────────────

	private TypeRef resolveSimple(String written, String simple, TypeModel context, boolean searchInherited) {
		// 1. nested types of the enclosing chain (the innermost first), and the types themselves
		for (TypeModel scope = context; scope != null; scope = scope.enclosing()) {
			if (scope.simpleName().equals(simple)) {
				return TypeRef.resolved(written, scope);
			}
			Optional<TypeRef> nested = project(written, scope.fqn() + "." + simple);
			if (nested.isPresent()) {
				return nested.get();
			}
			// members inherited from project supertypes
			for (TypeModel supertype : searchInherited ? projectSupertypes(scope) : List.<TypeModel>of()) {
				Optional<TypeRef> inherited = project(written, supertype.fqn() + "." + simple);
				if (inherited.isPresent()) {
					return inherited.get();
				}
			}
		}
		List<ImportDeclaration> imports = context.file().unit().getImports();
		// 2. single-type imports
		for (ImportDeclaration declaration : imports) {
			if (declaration.isStatic() || declaration.isAsterisk()) {
				continue;
			}
			String imported = declaration.getNameAsString();
			if (imported.equals(simple) || imported.endsWith("." + simple)) {
				return byFullName(written, imported);
			}
		}
		// 3. the same package
		String packageName = context.packageName();
		Optional<TypeRef> samePackage = project(written, packageName.isEmpty() ? simple : packageName + "." + simple);
		if (samePackage.isPresent()) {
			return samePackage.get();
		}
		// 4. on-demand imports
		List<TypeModel> candidates = new ArrayList<>();
		List<String> externalPackages = new ArrayList<>();
		for (ImportDeclaration declaration : imports) {
			if (declaration.isStatic() || !declaration.isAsterisk()) {
				continue;
			}
			String prefix = declaration.getNameAsString();
			List<TypeModel> matches = model.typesNamed(prefix + "." + simple);
			if (!matches.isEmpty()) {
				candidates.addAll(matches);
			}
			else if (!model.isProjectPackage(prefix) && model.typesNamed(prefix).isEmpty()) {
				externalPackages.add(prefix);
			}
		}
		if (candidates.size() == 1) {
			return TypeRef.resolved(written, candidates.get(0));
		}
		if (candidates.size() > 1) {
			return TypeRef.ambiguous(written, candidates.stream().map(TypeModel::fqn).distinct().toList());
		}
		// 5. java.lang and JDK packages imported on demand
		if (isJdkType("java.lang." + simple)) {
			return TypeRef.jdk(written, "java.lang." + simple);
		}
		for (String external : externalPackages) {
			if (isJdkType(external + "." + simple)) {
				return TypeRef.jdk(written, external + "." + simple);
			}
		}
		List<String> nonJdk = externalPackages.stream().filter(p -> !p.startsWith("java.")).toList();
		if (nonJdk.size() == 1) {
			// the only on-demand import that could supply it; still not verifiable, so unresolved
			return TypeRef.unresolved(written, nonJdk.get(0) + "." + simple);
		}
		return TypeRef.unresolved(written, simple);
	}

	private TypeRef resolveQualified(String written, String name, TypeModel context, boolean searchInherited) {
		// A full name of a project type, or the name of a project type followed by nested names.
		List<TypeModel> exact = model.typesNamed(name);
		if (exact.size() == 1) {
			return TypeRef.resolved(written, exact.get(0));
		}
		if (exact.size() > 1) {
			return TypeRef.ambiguous(written, exact.stream().map(TypeModel::fqn).toList());
		}
		int dot = name.indexOf('.');
		String first = name.substring(0, dot);
		String rest = name.substring(dot);
		if (Character.isUpperCase(first.charAt(0))) {
			TypeRef head = resolveSimple(first, first, context, searchInherited);
			if (head.isProjectType()) {
				Optional<TypeRef> nested = project(written, head.target().fqn() + rest);
				return nested.orElseGet(() -> TypeRef.unresolved(written, head.target().fqn() + rest));
			}
			if (head.jdk()) {
				return TypeRef.jdk(written, head.name() + rest);
			}
			return TypeRef.unresolved(written, head.name() + rest);
		}
		return byFullName(written, name);
	}

	private TypeRef byFullName(String written, String fqn) {
		List<TypeModel> matches = model.typesNamed(fqn);
		if (matches.size() == 1) {
			return TypeRef.resolved(written, matches.get(0));
		}
		if (matches.size() > 1) {
			return TypeRef.ambiguous(written, matches.stream().map(TypeModel::fqn).toList());
		}
		if (isJdkType(fqn) || isJdkNested(fqn)) {
			return TypeRef.jdk(written, fqn);
		}
		return TypeRef.unresolved(written, fqn);
	}

	/** {@code java.util.Map.Entry}: a JDK nested type. */
	private static boolean isJdkNested(String fqn) {
		int dot = fqn.lastIndexOf('.');
		return dot > 0 && fqn.startsWith("java.") && isJdkType(fqn.substring(0, dot) + "$" + fqn.substring(dot + 1));
	}

	private Optional<TypeRef> project(String written, String fqn) {
		List<TypeModel> matches = model.typesNamed(fqn);
		if (matches.size() == 1) {
			return Optional.of(TypeRef.resolved(written, matches.get(0)));
		}
		if (matches.size() > 1) {
			return Optional.of(TypeRef.ambiguous(written, matches.stream().map(TypeModel::fqn).toList()));
		}
		return Optional.empty();
	}

	/** Type parameters of enclosing methods, constructors and types hide types of the same name. */
	private static boolean isTypeVariable(String name, TypeModel context, Node usage) {
		Node node = usage;
		while (node != null) {
			if (node instanceof NodeWithTypeParameters<?> generic
					&& generic.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) {
				return true;
			}
			node = node.getParentNode().orElse(null);
		}
		for (TypeModel scope = context; scope != null; scope = scope.enclosing()) {
			if (scope.node() instanceof NodeWithTypeParameters<?> generic
					&& generic.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) {
				return true;
			}
		}
		return false;
	}

	private void collect(Type type, TypeModel context, boolean typeArgument, List<TypeUse> out) {
		if (type instanceof ArrayType array) {
			collect(array.getComponentType(), context, typeArgument, out);
		}
		else if (type instanceof ClassOrInterfaceType cls) {
			out.add(new TypeUse(resolve(cls.getNameWithScope(), context, cls), typeArgument));
			cls.getTypeArguments().ifPresent(args -> args.forEach(arg -> collect(arg, context, true, out)));
		}
		else if (type instanceof WildcardType wildcard) {
			wildcard.getExtendedType().ifPresent(bound -> collect(bound, context, true, out));
			wildcard.getSuperType().ifPresent(bound -> collect(bound, context, true, out));
		}
		else if (type instanceof UnionType union) {
			union.getElements().forEach(element -> collect(element, context, typeArgument, out));
		}
		else if (type instanceof IntersectionType intersection) {
			intersection.getElements().forEach(element -> collect(element, context, typeArgument, out));
		}
	}

	private static String stripGenerics(String written) {
		int angle = written.indexOf('<');
		String name = angle >= 0 ? written.substring(0, angle) : written;
		return name.replace("[]", "").replace("...", "").trim();
	}

}
