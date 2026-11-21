package com.repointelligence.analysis.orchestration;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.spi.ArchitectureResult;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.ApiEndpoint;
import com.repointelligence.architecture.domain.CodeSymbol;
import com.repointelligence.architecture.domain.DependencyEdge;
import com.repointelligence.architecture.domain.EntityRelationship;
import com.repointelligence.architecture.domain.LayerAssignment;
import com.repointelligence.architecture.domain.ModuleDependency;
import com.repointelligence.architecture.domain.PatternCandidate;
import com.repointelligence.common.security.SecretRedactor;
import com.repointelligence.repository.domain.RepositorySnapshot;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stores an {@link ArchitectureResult} inside the analyzer's result transaction. Symbols are written
 * first (enclosing types before members) and the result's keys become database ids; an item naming an
 * unknown key is dropped and counted. Text from the repository is redacted and length-capped like every
 * other result. The session is flushed and cleared in batches so large graphs do not pile up in memory.
 */
final class ArchitectureResultWriter {

	private static final int BATCH = 1000;

	private static final int NAME_MAX = 1024;

	private static final int SIGNATURE_MAX = 2048;

	private static final int ROUTE_MAX = 2048;

	private static final int ATTRIBUTE_MAX = 200;

	private final EntityManager entityManager;

	private final Function<Map<String, Object>, Map<String, Object>> redactMap;

	private final UUID snapshotId;

	private final UUID runId;

	private RepositorySnapshot snapshot;

	private AnalysisRun run;

	private int pending;

	private int dropped;

	ArchitectureResultWriter(EntityManager entityManager, Function<Map<String, Object>, Map<String, Object>> redactMap,
			UUID snapshotId, UUID runId) {
		this.entityManager = entityManager;
		this.redactMap = redactMap;
		this.snapshotId = snapshotId;
		this.runId = runId;
		references();
	}

	/** @return limitations to record, e.g. how many items referred to unknown symbols */
	List<String> write(ArchitectureResult result) {
		if (result.symbols().isEmpty() && result.moduleDependencies().isEmpty()) {
			return List.of();
		}
		Map<String, UUID> ids = writeSymbols(result.symbols());
		for (ArchitectureResult.Edge edge : result.edges()) {
			UUID source = ids.get(edge.sourceKey());
			UUID target = edge.targetKey() == null ? null : ids.get(edge.targetKey());
			if (source == null || edge.targetKey() != null && target == null) {
				dropped++;
				continue;
			}
			DependencyEdge row = new DependencyEdge(snapshot, run, symbol(source), edge.type(), edge.resolution());
			if (target != null) {
				row.setTarget(symbol(target));
			}
			row.setTargetUnresolvedName(text(edge.targetName(), NAME_MAX));
			row.setEvidence(redactMap.apply(edge.evidence()));
			persist(row);
		}
		for (ArchitectureResult.Endpoint endpoint : result.endpoints()) {
			UUID controller = ids.get(endpoint.controllerKey());
			UUID handler = ids.get(endpoint.handlerKey());
			if (controller == null || handler == null) {
				dropped++;
				continue;
			}
			ApiEndpoint row = new ApiEndpoint(snapshot, run, endpoint.httpMethod(), text(endpoint.route(), ROUTE_MAX),
					symbol(controller), symbol(handler), text(endpoint.filePath(), NAME_MAX));
			row.setRouteResolved(endpoint.routeResolved());
			row.setRequestType(text(endpoint.requestType(), NAME_MAX));
			row.setResponseType(text(endpoint.responseType(), NAME_MAX));
			row.setLineNumber(endpoint.line());
			persist(row);
		}
		for (ArchitectureResult.EntityRelation relation : result.entityRelations()) {
			UUID source = ids.get(relation.sourceKey());
			UUID target = relation.targetKey() == null ? null : ids.get(relation.targetKey());
			if (source == null || relation.targetKey() != null && target == null) {
				dropped++;
				continue;
			}
			EntityRelationship row = new EntityRelationship(snapshot, run, symbol(source), relation.type());
			if (target != null) {
				row.setTarget(symbol(target));
			}
			row.setTargetUnresolvedName(text(relation.targetName(), NAME_MAX));
			row.setFieldName(text(relation.fieldName(), 255));
			row.setAttributes(redactAttributes(relation.attributes()));
			row.setEvidence(redactMap.apply(relation.evidence()));
			persist(row);
		}
		for (ArchitectureResult.Layer layer : result.layers()) {
			UUID symbol = ids.get(layer.symbolKey());
			if (symbol == null) {
				dropped++;
				continue;
			}
			LayerAssignment row = new LayerAssignment(snapshot, run, symbol(symbol), layer.layer(), layer.confidence(),
					layer.method());
			row.setEvidence(redactEvidence(layer.evidence()));
			persist(row);
		}
		for (ArchitectureResult.ModuleDependency dependency : result.moduleDependencies()) {
			ModuleDependency row = new ModuleDependency(snapshot, run, text(dependency.sourceModulePath(), NAME_MAX),
					text(dependency.targetModulePath(), NAME_MAX));
			row.setEvidence(redactEvidence(dependency.evidence()));
			persist(row);
		}
		for (ArchitectureResult.Pattern pattern : result.patterns()) {
			List<UUID> symbolIds = pattern.symbolKeys().stream().map(ids::get).toList();
			if (symbolIds.contains(null)) {
				dropped++;
				continue;
			}
			PatternCandidate row = new PatternCandidate(snapshot, run, pattern.pattern(), symbolIds, pattern.confidence());
			row.setEvidence(redactMap.apply(pattern.evidence()));
			persist(row);
		}
		entityManager.flush();
		entityManager.clear();
		return dropped == 0 ? List.of()
				: List.of(dropped + " architecture items referred to symbols that were not stored and were left out.");
	}

	private Map<String, UUID> writeSymbols(List<ArchitectureResult.Symbol> symbols) {
		Map<String, Integer> depth = new HashMap<>();
		Map<String, String> parents = new HashMap<>();
		symbols.forEach(s -> parents.put(s.key(), s.parentKey()));
		for (ArchitectureResult.Symbol symbol : symbols) {
			depth.put(symbol.key(), depthOf(symbol.key(), parents, 0));
		}
		List<ArchitectureResult.Symbol> ordered = new ArrayList<>(symbols);
		ordered.sort(Comparator.comparingInt(s -> depth.get(s.key())));
		Map<String, UUID> ids = new LinkedHashMap<>();
		int currentDepth = -1;
		for (ArchitectureResult.Symbol symbol : ordered) {
			int d = depth.get(symbol.key());
			if (d != currentDepth) {
				// parents must be in the database before children reference them
				entityManager.flush();
				currentDepth = d;
			}
			if (ids.containsKey(symbol.key()) || symbol.parentKey() != null && !ids.containsKey(symbol.parentKey())) {
				dropped++;
				continue;
			}
			CodeSymbol row = new CodeSymbol(snapshot, run, symbol.type(), text(symbol.fullyQualifiedName(), NAME_MAX),
					text(symbol.filePath(), NAME_MAX));
			row.setSignature(text(symbol.signature(), SIGNATURE_MAX));
			if (symbol.parentKey() != null) {
				row.setParent(symbol(ids.get(symbol.parentKey())));
			}
			row.setStartLine(symbol.startLine());
			row.setEndLine(symbol.endLine());
			row.setAnnotations(symbol.annotations().isEmpty() ? null : symbol.annotations().stream()
				.map(a -> new AnnotationInfo(text(a.name(), 255), redactAttributes(a.attributes())))
				.toList());
			row.setModifiers(symbol.modifiers().isEmpty() ? null : symbol.modifiers());
			row.setModulePath(text(symbol.modulePath(), NAME_MAX));
			persist(row);
			ids.put(symbol.key(), row.getId());
		}
		entityManager.flush();
		return ids;
	}

	private static int depthOf(String key, Map<String, String> parents, int guard) {
		String parent = parents.get(key);
		if (parent == null || guard > 64 || !parents.containsKey(parent)) {
			return 0;
		}
		return 1 + depthOf(parent, parents, guard + 1);
	}

	private void persist(Object row) {
		entityManager.persist(row);
		if (++pending % BATCH == 0) {
			entityManager.flush();
			entityManager.clear();
			references();
		}
	}

	private void references() {
		snapshot = entityManager.getReference(RepositorySnapshot.class, snapshotId);
		run = entityManager.getReference(AnalysisRun.class, runId);
	}

	private CodeSymbol symbol(UUID id) {
		return entityManager.getReference(CodeSymbol.class, id);
	}

	private Map<String, String> redactAttributes(Map<String, String> attributes) {
		if (attributes == null || attributes.isEmpty()) {
			return null;
		}
		Map<String, String> result = new LinkedHashMap<>();
		attributes.forEach((key, value) -> result.put(text(key, 100), text(SecretRedactor.redact(value), ATTRIBUTE_MAX)));
		return result;
	}

	private static List<Evidence> redactEvidence(List<Evidence> evidence) {
		if (evidence == null || evidence.isEmpty()) {
			return null;
		}
		return evidence.stream()
			.map(e -> new Evidence(text(e.path(), NAME_MAX), e.line(), text(SecretRedactor.redact(e.signal()), 500)))
			.toList();
	}

	private static String text(String value, int max) {
		if (value == null) {
			return null;
		}
		String redacted = SecretRedactor.redact(value);
		return redacted.length() <= max ? redacted : redacted.substring(0, max);
	}

}
