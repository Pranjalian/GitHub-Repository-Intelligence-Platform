package com.repointelligence.analysis.packs.java.architecture;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.Type;
import com.repointelligence.analysis.spi.AnalysisCancelledException;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Java sources of a snapshot, parsed once with JavaParser and indexed by fully qualified name. Only
 * the syntax tree is read: nothing is compiled, loaded or executed. A file that cannot be read or parsed
 * becomes a warning and the rest of the project is still modelled.
 */
public final class JavaProjectModel {

	/** One parsed {@code .java} file. */
	public record ParsedFile(String path, String modulePath, String packageName, CompilationUnit unit) {
	}

	/** A field (one per declared variable) or record component. */
	public record FieldModel(String key, String name, Type type, TypeModel owner, BodyDeclaration<?> declaration,
			VariableDeclarator variable, Parameter recordComponent, int line) {

		public boolean isStatic() {
			return declaration instanceof FieldDeclaration field && field.isStatic();
		}

		public boolean isFinal() {
			return recordComponent != null || declaration instanceof FieldDeclaration field && field.isFinal();
		}

		public boolean hasInitializer() {
			return variable != null && variable.getInitializer().isPresent();
		}

	}

	/** A method or constructor. */
	public record CallableModel(String key, String name, SymbolType kind, CallableDeclaration<?> node, TypeModel owner,
			int arity, boolean varArgs) {

		public boolean accepts(int arguments) {
			return varArgs ? arguments >= arity - 1 : arguments == arity;
		}

	}

	/** A class, interface, enum, record or annotation type, top-level or nested. */
	public static final class TypeModel {

		private final String key;

		private final String fqn;

		private final SymbolType kind;

		private final TypeDeclaration<?> node;

		private final ParsedFile file;

		private final TypeModel enclosing;

		private final List<FieldModel> fields = new ArrayList<>();

		private final List<CallableModel> methods = new ArrayList<>();

		private final List<CallableModel> constructors = new ArrayList<>();

		TypeModel(String key, String fqn, SymbolType kind, TypeDeclaration<?> node, ParsedFile file, TypeModel enclosing) {
			this.key = key;
			this.fqn = fqn;
			this.kind = kind;
			this.node = node;
			this.file = file;
			this.enclosing = enclosing;
		}

		public String key() {
			return key;
		}

		public String fqn() {
			return fqn;
		}

		public String simpleName() {
			return node.getNameAsString();
		}

		public SymbolType kind() {
			return kind;
		}

		public TypeDeclaration<?> node() {
			return node;
		}

		public ParsedFile file() {
			return file;
		}

		public String filePath() {
			return file.path();
		}

		public String packageName() {
			return file.packageName();
		}

		public TypeModel enclosing() {
			return enclosing;
		}

		public List<FieldModel> fields() {
			return Collections.unmodifiableList(fields);
		}

		public List<CallableModel> methods() {
			return Collections.unmodifiableList(methods);
		}

		public List<CallableModel> constructors() {
			return Collections.unmodifiableList(constructors);
		}

		public int line() {
			return node.getBegin().map(p -> p.line).orElse(1);
		}

		public boolean isInterface() {
			return kind == SymbolType.INTERFACE;
		}

		public boolean isAbstract() {
			return node instanceof ClassOrInterfaceDeclaration cls && cls.isAbstract();
		}

		@Override
		public String toString() {
			return fqn;
		}

	}

	private final List<ParsedFile> files;

	private final List<TypeModel> types;

	private final Map<String, List<TypeModel>> byFqn;

	private final Set<String> packages;

	private final List<String> warnings;

	private JavaProjectModel(List<ParsedFile> files, List<TypeModel> types, List<String> warnings) {
		this.files = List.copyOf(files);
		this.types = List.copyOf(types);
		this.byFqn = types.stream().collect(Collectors.groupingBy(TypeModel::fqn, LinkedHashMap::new, Collectors.toList()));
		this.packages = files.stream().map(ParsedFile::packageName).collect(Collectors.toUnmodifiableSet());
		this.warnings = List.copyOf(warnings);
	}

	public List<ParsedFile> files() {
		return files;
	}

	/** Every type in source order, nested types right after their enclosing type. */
	public List<TypeModel> types() {
		return types;
	}

	/** Types with this fully qualified name: usually one, several when two modules declare the same name. */
	public List<TypeModel> typesNamed(String fqn) {
		return byFqn.getOrDefault(fqn, List.of());
	}

	public boolean isProjectPackage(String packageName) {
		return packages.contains(packageName);
	}

	public List<String> warnings() {
		return warnings;
	}

	/**
	 * Parses the Java files of the snapshot.
	 * @param includeTests whether test sources are modelled too
	 * @param maxFileBytes larger files are skipped with a warning
	 * @param modulePathOf the build module a file belongs to
	 */
	public static JavaProjectModel parse(WorkspaceView workspace, List<FileInfo> fileInfos, boolean includeTests,
			long maxFileBytes, Function<String, String> modulePathOf, CancellationToken cancellation) {
		JavaParser parser = new JavaParser(new ParserConfiguration()
			.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
			.setAttributeComments(false));
		List<String> warnings = new ArrayList<>();
		List<ParsedFile> parsed = new ArrayList<>();
		for (FileInfo file : fileInfos) {
			if (!file.extracted() || !file.path().endsWith(".java") || "module-info.java".equals(baseName(file.path()))
					|| "package-info.java".equals(baseName(file.path()))) {
				continue;
			}
			if (!includeTests && file.fileType() == FileType.TEST) {
				continue;
			}
			if (cancellation.isCancelled()) {
				throw new AnalysisCancelledException();
			}
			if (file.sizeBytes() > maxFileBytes) {
				warnings.add(file.path() + ": skipped, larger than " + maxFileBytes / 1024 + " KB.");
				continue;
			}
			try {
				ParseResult<CompilationUnit> result = parser.parse(workspace.readString(file.path(), maxFileBytes));
				Optional<CompilationUnit> unit = result.getResult();
				if (unit.isEmpty() || !result.isSuccessful()) {
					warnings.add(file.path() + ": could not be parsed; its symbols are missing from the architecture.");
					continue;
				}
				String packageName = unit.get().getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
				parsed.add(new ParsedFile(file.path(), modulePathOf.apply(file.path()), packageName, unit.get()));
			}
			catch (IOException | RuntimeException ex) {
				warnings.add(file.path() + ": could not be read; its symbols are missing from the architecture.");
			}
		}
		List<TypeModel> types = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (ParsedFile file : parsed) {
			for (TypeDeclaration<?> type : file.unit().getTypes()) {
				collect(type, file, null, types, keys);
			}
		}
		return new JavaProjectModel(parsed, types, warnings);
	}

	private static void collect(TypeDeclaration<?> node, ParsedFile file, TypeModel enclosing, List<TypeModel> out,
			Set<String> keys) {
		String fqn = enclosing != null ? enclosing.fqn() + "." + node.getNameAsString()
				: file.packageName().isEmpty() ? node.getNameAsString() : file.packageName() + "." + node.getNameAsString();
		SymbolType kind = kindOf(node);
		TypeModel type = new TypeModel(unique(file.path() + "!" + fqn, keys), fqn, kind, node, file, enclosing);
		out.add(type);
		for (BodyDeclaration<?> member : node.getMembers()) {
			if (member instanceof FieldDeclaration field) {
				for (VariableDeclarator variable : field.getVariables()) {
					type.fields.add(new FieldModel(unique(type.key + "#" + variable.getNameAsString(), keys),
							variable.getNameAsString(), variable.getType(), type, field, variable, null,
							variable.getBegin().map(p -> p.line).orElse(type.line())));
				}
			}
			else if (member instanceof MethodDeclaration method) {
				type.methods.add(callable(type, method, SymbolType.METHOD, method.getNameAsString(), keys));
			}
			else if (member instanceof ConstructorDeclaration constructor) {
				type.constructors.add(callable(type, constructor, SymbolType.CONSTRUCTOR, "<init>", keys));
			}
		}
		if (node instanceof RecordDeclaration record) {
			for (Parameter component : record.getParameters()) {
				type.fields.add(new FieldModel(unique(type.key + "#" + component.getNameAsString(), keys),
						component.getNameAsString(), component.getType(), type, record, null, component,
						component.getBegin().map(p -> p.line).orElse(type.line())));
			}
		}
		for (BodyDeclaration<?> member : node.getMembers()) {
			if (member instanceof TypeDeclaration<?> nested) {
				collect(nested, file, type, out, keys);
			}
		}
	}

	private static CallableModel callable(TypeModel owner, CallableDeclaration<?> node, SymbolType kind, String name,
			Set<String> keys) {
		boolean varArgs = !node.getParameters().isEmpty() && node.getParameters().getLast().orElseThrow().isVarArgs();
		return new CallableModel(unique(owner.key + "#" + name + "(" + parameterTypes(node) + ")", keys), name, kind,
				node, owner, node.getParameters().size(), varArgs);
	}

	/** Parameter types as written, comma separated: the readable part of a signature. */
	static String parameterTypes(CallableDeclaration<?> node) {
		return node.getParameters()
			.stream()
			.map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : ""))
			.collect(Collectors.joining(", "));
	}

	private static SymbolType kindOf(TypeDeclaration<?> node) {
		if (node instanceof ClassOrInterfaceDeclaration cls) {
			return cls.isInterface() ? SymbolType.INTERFACE : SymbolType.CLASS;
		}
		if (node instanceof EnumDeclaration) {
			return SymbolType.ENUM;
		}
		if (node instanceof RecordDeclaration) {
			return SymbolType.RECORD;
		}
		if (node instanceof AnnotationDeclaration) {
			return SymbolType.ANNOTATION;
		}
		return SymbolType.CLASS;
	}

	private static String unique(String key, Set<String> keys) {
		String candidate = key;
		int n = 2;
		while (!keys.add(candidate)) {
			candidate = key + "~" + n++;
		}
		return candidate;
	}

	private static String baseName(String path) {
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

}
