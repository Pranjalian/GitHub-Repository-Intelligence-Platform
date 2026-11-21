package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import com.repointelligence.architecture.domain.LayerMethod;
import com.repointelligence.architecture.domain.PatternType;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.common.model.Confidence;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What an architecture analyzer returns: plain DTOs that refer to each other by {@code key}, a string
 * unique within the result. The orchestrator stores the symbols first and turns keys into database ids;
 * an item that names an unknown key is dropped with a warning, never stored half-linked.
 */
public record ArchitectureResult(List<Symbol> symbols, List<Edge> edges, List<Endpoint> endpoints,
		List<EntityRelation> entityRelations, List<Layer> layers, List<ModuleDependency> moduleDependencies,
		List<Pattern> patterns) {

	public ArchitectureResult {
		symbols = copy(symbols);
		edges = copy(edges);
		endpoints = copy(endpoints);
		entityRelations = copy(entityRelations);
		layers = copy(layers);
		moduleDependencies = copy(moduleDependencies);
		patterns = copy(patterns);
	}

	public static final ArchitectureResult EMPTY = new ArchitectureResult(null, null, null, null, null, null, null);

	public boolean isEmpty() {
		return symbols.isEmpty() && moduleDependencies.isEmpty();
	}

	private static <T> List<T> copy(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	/** A type or member. {@code parentKey} is the enclosing type; it must appear earlier in the list. */
	public record Symbol(String key, SymbolType type, String fullyQualifiedName, String signature, String parentKey,
			String filePath, Integer startLine, Integer endLine, List<AnnotationInfo> annotations,
			List<String> modifiers, String modulePath) {

		public Symbol {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(fullyQualifiedName, "fullyQualifiedName");
			Objects.requireNonNull(filePath, "filePath");
			annotations = annotations == null ? List.of() : List.copyOf(annotations);
			modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
		}

	}

	/**
	 * A relationship. Exactly as much is said as is known: {@code targetKey} for a symbol of the snapshot
	 * ({@code RESOLVED}), otherwise the name as written or imported ({@code UNRESOLVED}), or the candidate
	 * names ({@code AMBIGUOUS}).
	 */
	public record Edge(String sourceKey, String targetKey, String targetName, RelationshipType type,
			EdgeResolution resolution, Map<String, Object> evidence) {

		public Edge {
			Objects.requireNonNull(sourceKey, "sourceKey");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(resolution, "resolution");
			if (targetKey == null && (targetName == null || targetName.isBlank())) {
				throw new IllegalArgumentException("An edge needs a target key or a target name");
			}
			if (resolution == EdgeResolution.RESOLVED && targetKey == null) {
				throw new IllegalArgumentException("A resolved edge needs a target key");
			}
			evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
		}

	}

	public record Endpoint(String httpMethod, String route, boolean routeResolved, String controllerKey,
			String handlerKey, String requestType, String responseType, String filePath, Integer line) {

		public Endpoint {
			Objects.requireNonNull(httpMethod, "httpMethod");
			Objects.requireNonNull(route, "route");
			Objects.requireNonNull(controllerKey, "controllerKey");
			Objects.requireNonNull(handlerKey, "handlerKey");
			Objects.requireNonNull(filePath, "filePath");
		}

	}

	public record EntityRelation(String sourceKey, String targetKey, String targetName, EntityRelationshipType type,
			String fieldName, Map<String, String> attributes, Map<String, Object> evidence) {

		public EntityRelation {
			Objects.requireNonNull(sourceKey, "sourceKey");
			Objects.requireNonNull(type, "type");
			if (targetKey == null && (targetName == null || targetName.isBlank())) {
				throw new IllegalArgumentException("An entity relation needs a target key or a target name");
			}
			attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
			evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
		}

	}

	public record Layer(String symbolKey, ArchitectureLayer layer, Confidence confidence, LayerMethod method,
			List<Evidence> evidence) {

		public Layer {
			Objects.requireNonNull(symbolKey, "symbolKey");
			Objects.requireNonNull(layer, "layer");
			Objects.requireNonNull(confidence, "confidence");
			Objects.requireNonNull(method, "method");
			evidence = evidence == null ? List.of() : List.copyOf(evidence);
		}

	}

	public record ModuleDependency(String sourceModulePath, String targetModulePath, List<Evidence> evidence) {

		public ModuleDependency {
			Objects.requireNonNull(sourceModulePath, "sourceModulePath");
			Objects.requireNonNull(targetModulePath, "targetModulePath");
			evidence = evidence == null ? List.of() : List.copyOf(evidence);
		}

	}

	public record Pattern(PatternType pattern, List<String> symbolKeys, Confidence confidence,
			Map<String, Object> evidence) {

		public Pattern {
			Objects.requireNonNull(pattern, "pattern");
			Objects.requireNonNull(confidence, "confidence");
			symbolKeys = symbolKeys == null ? List.of() : List.copyOf(symbolKeys);
			if (symbolKeys.isEmpty()) {
				throw new IllegalArgumentException("A pattern candidate names at least one symbol");
			}
			evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
		}

	}

}
