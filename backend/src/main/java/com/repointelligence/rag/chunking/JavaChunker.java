package com.repointelligence.rag.chunking;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Java chunks from the syntax tree (the same JavaParser and language level as the MVP 2 symbol graph; the
 * source is only parsed, never compiled or run): one {@link ChunkType#CLASS} chunk per type with its
 * declaration, Javadoc, fields and bodiless (abstract/interface) methods, and one {@link ChunkType#METHOD}
 * chunk per method or constructor with a body, including its Javadoc. The first top-level type's chunk also
 * starts at line 1, so package and imports are searchable. Local classes stay inside their method's chunk.
 * Test sources are chunked too, which the symbol graph does not model.
 */
final class JavaChunker {

	private JavaChunker() {
	}

	/** One chunk's content before splitting, with the names it is stored under. */
	record Unit(ChunkType type, String className, String methodName, String section, Piece piece) {
	}

	/** Null when the file does not parse; the caller then falls back to labelled line windows. */
	static List<Unit> chunk(String text) {
		JavaParser parser = new JavaParser(
				new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
		ParseResult<CompilationUnit> result = parser.parse(text);
		if (!result.isSuccessful() || result.getResult().isEmpty()) {
			return null;
		}
		CompilationUnit unit = result.getResult().get();
		String[] lines = LineWindows.lines(text);
		String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
		List<Unit> units = new ArrayList<>();
		@SuppressWarnings("rawtypes")
		List<TypeDeclaration> types = unit.findAll(TypeDeclaration.class);
		boolean first = true;
		for (TypeDeclaration<?> type : sorted(types)) {
			if (insideCallable(type) || type.getRange().isEmpty()) {
				continue;
			}
			String className = packageName + nestedName(type);
			int start = startWithComment(type);
			if (first && type.isTopLevelType()) {
				start = 1;
				first = false;
			}
			int end = type.getRange().get().end.line;
			List<Elided> elided = new ArrayList<>();
			for (BodyDeclaration<?> member : type.getMembers()) {
				if (member.getRange().isEmpty()) {
					continue;
				}
				if (member instanceof CallableDeclaration<?> callable && hasBody(callable)) {
					String signature = callable.getSignature().asString();
					elided.add(new Elided(startWithComment(member), member.getRange().get().end.line,
							(callable instanceof ConstructorDeclaration ? "constructor " : "method ") + signature));
					units.add(new Unit(ChunkType.METHOD, className, callable.getNameAsString(), signature,
							Piece.ofLines(lines, startWithComment(member), member.getRange().get().end.line)));
				}
				else if (member instanceof TypeDeclaration<?> nested) {
					elided.add(new Elided(startWithComment(member), member.getRange().get().end.line,
							"nested type " + nested.getNameAsString()));
				}
			}
			units.add(new Unit(ChunkType.CLASS, className, null, kind(type) + " " + type.getNameAsString(),
					header(lines, start, end, elided)));
		}
		units.sort(Comparator.comparingInt((Unit u) -> u.piece().firstLine()).thenComparing(u -> u.type().ordinal()));
		return units;
	}

	private record Elided(int start, int end, String what) {
	}

	/** The type's lines with each separately chunked member replaced by one marker comment. */
	private static Piece header(String[] lines, int start, int end, List<Elided> elided) {
		Piece piece = new Piece();
		int line = start;
		List<Elided> ordered = elided.stream().sorted(Comparator.comparingInt(Elided::start)).toList();
		for (Elided e : ordered) {
			while (line < e.start() && line <= end) {
				piece.add(line, lines[line - 1]);
				line++;
			}
			if (e.start() >= line) {
				String indent = lines[e.start() - 1].replaceAll("^(\\s*).*$", "$1");
				piece.add(e.start(), indent + "// ... " + e.what() + " (lines " + e.start() + "-" + e.end()
						+ ", a separate chunk)");
				line = e.end() + 1;
			}
		}
		while (line <= end) {
			piece.add(line, lines[line - 1]);
			line++;
		}
		return piece;
	}

	private static boolean hasBody(CallableDeclaration<?> callable) {
		if (callable instanceof MethodDeclaration method) {
			return method.getBody().isPresent();
		}
		return callable instanceof ConstructorDeclaration;
	}

	/** First line of a node including a comment attached to it (usually its Javadoc). */
	private static int startWithComment(Node node) {
		int start = node.getRange().map(r -> r.begin.line).orElse(1);
		Optional<Range> comment = node.getComment().flatMap(Node::getRange);
		return comment.map(r -> Math.min(start, r.begin.line)).orElse(start);
	}

	private static boolean insideCallable(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			if (parent.get() instanceof CallableDeclaration<?>) {
				return true;
			}
			parent = parent.get().getParentNode();
		}
		return false;
	}

	private static String nestedName(TypeDeclaration<?> type) {
		StringBuilder name = new StringBuilder(type.getNameAsString());
		Optional<Node> parent = type.getParentNode();
		while (parent.isPresent()) {
			if (parent.get() instanceof TypeDeclaration<?> outer) {
				name.insert(0, outer.getNameAsString() + ".");
			}
			parent = parent.get().getParentNode();
		}
		return name.toString();
	}

	private static String kind(TypeDeclaration<?> type) {
		if (type.isEnumDeclaration()) {
			return "enum";
		}
		if (type.isRecordDeclaration()) {
			return "record";
		}
		if (type.isAnnotationDeclaration()) {
			return "annotation";
		}
		if (type.isClassOrInterfaceDeclaration() && type.asClassOrInterfaceDeclaration().isInterface()) {
			return "interface";
		}
		return "class";
	}

	@SuppressWarnings("rawtypes")
	private static List<TypeDeclaration<?>> sorted(List<TypeDeclaration> types) {
		List<TypeDeclaration<?>> result = new ArrayList<>();
		for (TypeDeclaration type : types) {
			result.add(type);
		}
		result.sort(Comparator.comparingInt(t -> t.getRange().map(r -> r.begin.line).orElse(0)));
		return result;
	}

}
