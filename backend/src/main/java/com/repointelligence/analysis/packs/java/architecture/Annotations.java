package com.repointelligence.analysis.packs.java.architecture;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.repointelligence.architecture.domain.AnnotationInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads annotations from the syntax tree. Attribute values are kept only when they are literals
 * (strings, numbers, booleans, characters, enum constants, class literals, arrays and concatenations of
 * those); anything else is left out, never evaluated.
 */
public final class Annotations {

	private Annotations() {
	}

	public static List<AnnotationInfo> of(Node node) {
		if (!(node instanceof NodeWithAnnotations<?> annotated)) {
			return List.of();
		}
		List<AnnotationInfo> result = new ArrayList<>();
		for (AnnotationExpr annotation : annotated.getAnnotations()) {
			result.add(new AnnotationInfo(annotation.getNameAsString(), attributes(annotation)));
		}
		return result;
	}

	/** Literal attributes; a single-member annotation's value is stored under {@code value}. */
	public static Map<String, String> attributes(AnnotationExpr annotation) {
		Map<String, String> attributes = new LinkedHashMap<>();
		if (annotation instanceof SingleMemberAnnotationExpr single) {
			literal(single.getMemberValue()).ifPresent(value -> attributes.put("value", value));
		}
		else if (annotation instanceof NormalAnnotationExpr normal) {
			for (MemberValuePair pair : normal.getPairs()) {
				literal(pair.getValue()).ifPresent(value -> attributes.put(pair.getNameAsString(), value));
			}
		}
		return attributes;
	}

	/** The annotation with one of these simple names, if present. */
	public static Optional<AnnotationExpr> find(Node node, String... simpleNames) {
		if (!(node instanceof NodeWithAnnotations<?> annotated)) {
			return Optional.empty();
		}
		Set<String> names = Set.of(simpleNames);
		return annotated.getAnnotations().stream().filter(a -> names.contains(simpleName(a))).findFirst();
	}

	public static boolean has(Node node, String... simpleNames) {
		return find(node, simpleNames).isPresent();
	}

	public static boolean hasAny(Node node, Set<String> simpleNames) {
		return node instanceof NodeWithAnnotations<?> annotated
				&& annotated.getAnnotations().stream().anyMatch(a -> simpleNames.contains(simpleName(a)));
	}

	public static String simpleName(AnnotationExpr annotation) {
		String name = annotation.getNameAsString();
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1) : name;
	}

	/** The expression of an attribute ({@code value} for single-member annotations), unevaluated. */
	public static Optional<Expression> member(AnnotationExpr annotation, String name) {
		if (annotation instanceof SingleMemberAnnotationExpr single) {
			return "value".equals(name) ? Optional.of(single.getMemberValue()) : Optional.empty();
		}
		if (annotation instanceof NormalAnnotationExpr normal) {
			return normal.getPairs()
				.stream()
				.filter(pair -> pair.getNameAsString().equals(name))
				.map(MemberValuePair::getValue)
				.findFirst();
		}
		return Optional.empty();
	}

	public static List<String> modifiers(Node node) {
		if (!(node instanceof NodeWithModifiers<?> modified)) {
			return List.of();
		}
		return modified.getModifiers().stream().map(m -> m.getKeyword().asString()).toList();
	}

	/** A literal value as text, or empty when the expression is not a literal. */
	public static Optional<String> literal(Expression expression) {
		if (expression instanceof StringLiteralExpr string) {
			return Optional.of(string.asString());
		}
		if (expression instanceof TextBlockLiteralExpr text) {
			return Optional.of(text.asString());
		}
		if (expression instanceof IntegerLiteralExpr || expression instanceof LongLiteralExpr
				|| expression instanceof DoubleLiteralExpr || expression instanceof BooleanLiteralExpr) {
			return Optional.of(expression.toString());
		}
		if (expression instanceof CharLiteralExpr character) {
			return Optional.of(character.asChar() + "");
		}
		if (expression instanceof ClassExpr classExpr) {
			return Optional.of(classExpr.getType().asString() + ".class");
		}
		if (expression instanceof FieldAccessExpr access && isEnumConstant(access)) {
			return Optional.of(access.toString());
		}
		if (expression instanceof NameExpr name && name.getNameAsString().chars()
			.allMatch(c -> Character.isUpperCase(c) || c == '_' || Character.isDigit(c))) {
			// a statically imported enum constant such as LAZY
			return Optional.of(name.getNameAsString());
		}
		if (expression instanceof ArrayInitializerExpr array) {
			List<Optional<String>> values = array.getValues().stream().map(Annotations::literal).toList();
			if (values.stream().allMatch(Optional::isPresent)) {
				return Optional.of(values.stream().map(Optional::get).collect(Collectors.joining(", ", "[", "]")));
			}
			return Optional.empty();
		}
		if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
			Optional<String> left = literal(binary.getLeft());
			Optional<String> right = literal(binary.getRight());
			if (left.isPresent() && right.isPresent() && isStringy(binary.getLeft()) && isStringy(binary.getRight())) {
				return Optional.of(left.get() + right.get());
			}
		}
		return Optional.empty();
	}

	/** The elements of a literal array attribute ({@code {"/a", "/b"}}) or the single literal. */
	public static List<String> literalValues(Expression expression) {
		if (expression instanceof ArrayInitializerExpr array) {
			List<String> values = new ArrayList<>();
			for (Expression value : array.getValues()) {
				literal(value).ifPresent(values::add);
			}
			return values;
		}
		return literal(expression).map(List::of).orElse(List.of());
	}

	private static boolean isStringy(Expression expression) {
		return expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr
				|| expression instanceof BinaryExpr;
	}

	/** {@code FetchType.LAZY}, {@code CascadeType.ALL}: an upper-case constant on a capitalised scope. */
	private static boolean isEnumConstant(FieldAccessExpr access) {
		String name = access.getNameAsString();
		String scope = access.getScope().toString();
		String last = scope.contains(".") ? scope.substring(scope.lastIndexOf('.') + 1) : scope;
		return !last.isEmpty() && Character.isUpperCase(last.charAt(0))
				&& Arrays.stream(name.split("")).allMatch(c -> c.equals("_") || !c.equals(c.toLowerCase()) || c.matches("\\d"));
	}

}
