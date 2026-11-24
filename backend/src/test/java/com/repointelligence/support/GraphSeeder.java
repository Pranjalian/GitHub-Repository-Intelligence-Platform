package com.repointelligence.support;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a snapshot, a completed run and a symbol graph directly in the database, so graph queries and
 * the impact analyzer can be tested on exact, hand-drawn graphs and on large synthetic ones. Committed
 * data: callers delete the repository rows afterwards.
 */
public final class GraphSeeder {

	private static final AtomicInteger COUNTER = new AtomicInteger();

	private final JdbcTemplate jdbc;

	private final UUID repositoryId;

	private final UUID snapshotId;

	private final UUID runId;

	private final Map<String, UUID> ids = new HashMap<>();

	private GraphSeeder(JdbcTemplate jdbc, UUID repositoryId, UUID snapshotId, UUID runId) {
		this.jdbc = jdbc;
		this.repositoryId = repositoryId;
		this.snapshotId = snapshotId;
		this.runId = runId;
	}

	public static GraphSeeder create(EntityManager em, PlatformTransactionManager transactions, JdbcTemplate jdbc) {
		int n = COUNTER.incrementAndGet();
		return new TransactionTemplate(transactions).execute(status -> {
			TrackedRepository repository = new TrackedRepository("graph-owner", "graph-repo-" + UUID.randomUUID(),
					"https://github.com/graph-owner/graph-repo-" + n);
			em.persist(repository);
			String sha = String.format("%40s", Integer.toHexString(0xabc000 + n)).replace(' ', 'd');
			RepositorySnapshot snapshot = new RepositorySnapshot(repository, sha);
			em.persist(snapshot);
			AnalysisRun run = new AnalysisRun(snapshot, RunStatus.COMPLETED);
			em.persist(run);
			return new GraphSeeder(jdbc, repository.getId(), snapshot.getId(), run.getId());
		});
	}

	public UUID repositoryId() {
		return repositoryId;
	}

	public UUID snapshotId() {
		return snapshotId;
	}

	public UUID runId() {
		return runId;
	}

	public UUID id(String key) {
		UUID id = ids.get(key);
		if (id == null) {
			throw new IllegalArgumentException("no symbol seeded as " + key);
		}
		return id;
	}

	/** A type, keyed by its fully qualified name. */
	public UUID type(String fqn, String kind, String module, String annotationNames) {
		String path = "src/main/java/" + fqn.replace('.', '/') + ".java";
		UUID id = jdbc.queryForObject("""
				INSERT INTO code_symbol (snapshot_id, run_id, symbol_type, fully_qualified_name, file_path, start_line, end_line,
				    annotations, module_path)
				VALUES (?, ?, ?, ?, ?, 1, 100, CAST(? AS jsonb), ?) RETURNING id
				""", UUID.class, snapshotId, runId, kind, fqn, path, annotationJson(annotationNames), module);
		ids.put(fqn, id);
		return id;
	}

	public UUID clazz(String fqn) {
		return type(fqn, "CLASS", null, "");
	}

	/** A method of a seeded type, keyed {@code Type#name(params)}. */
	public UUID method(String typeFqn, String name, String params) {
		UUID owner = id(typeFqn);
		String key = typeFqn + "#" + name + "(" + params + ")";
		UUID id = jdbc.queryForObject("""
				INSERT INTO code_symbol (snapshot_id, run_id, symbol_type, fully_qualified_name, signature, parent_symbol_id,
				    file_path, start_line, end_line, module_path)
				SELECT ?, ?, 'METHOD', ?, ?, ?, file_path, 10, 20, module_path FROM code_symbol WHERE id = ? RETURNING id
				""", UUID.class, snapshotId, runId, typeFqn + "#" + name, name + "(" + params + ")", owner, owner);
		ids.put(key, id);
		return id;
	}

	public UUID edge(UUID source, UUID target, String relationship) {
		return jdbc.queryForObject("""
				INSERT INTO dependency_edge (snapshot_id, run_id, source_symbol_id, target_symbol_id, relationship_type,
				    resolution, evidence)
				VALUES (?, ?, ?, ?, ?, 'RESOLVED', CAST(? AS jsonb)) RETURNING id
				""", UUID.class, snapshotId, runId, source, target, relationship, "{\"file\":\"A.java\",\"line\":7}");
	}

	public void ambiguousCall(UUID source, String... candidates) {
		jdbc.update("""
				INSERT INTO dependency_edge (snapshot_id, run_id, source_symbol_id, target_unresolved_name, relationship_type,
				    resolution, evidence)
				VALUES (?, ?, ?, 'x#m', 'CALLS', 'AMBIGUOUS', CAST(? AS jsonb))
				""", snapshotId, runId, source,
				"{\"file\":\"B.java\",\"line\":9,\"candidates\":[" + String.join(",", java.util.Arrays.stream(candidates)
					.map(c -> "\"" + c + "\"")
					.toList()) + "]}");
	}

	public void layer(UUID symbol, String layer) {
		jdbc.update("INSERT INTO layer_assignment (snapshot_id, run_id, symbol_id, layer, confidence, method) "
				+ "VALUES (?, ?, ?, ?, 'DETECTED', 'ANNOTATION')", snapshotId, runId, symbol, layer);
	}

	public void endpoint(UUID controller, UUID handler, String httpMethod, String route) {
		jdbc.update("INSERT INTO api_endpoint (snapshot_id, run_id, http_method, route, controller_symbol_id, handler_symbol_id, "
				+ "file_path, line_number) VALUES (?, ?, ?, ?, ?, ?, 'C.java', 12)", snapshotId, runId, httpMethod, route,
				controller, handler);
	}

	public void entityRelationship(UUID source, UUID target, String type, String field) {
		jdbc.update("INSERT INTO entity_relationship (snapshot_id, run_id, source_symbol_id, target_symbol_id, relationship_type, "
				+ "field_name) VALUES (?, ?, ?, ?, ?, ?)", snapshotId, runId, source, target, type, field);
	}

	public void moduleDependency(String source, String target) {
		jdbc.update("INSERT INTO module_dependency (snapshot_id, run_id, source_module_path, target_module_path, evidence) "
				+ "VALUES (?, ?, ?, ?, CAST(? AS jsonb))", snapshotId, runId, source, target,
				"[{\"path\":\"" + source + "/pom.xml\",\"line\":5,\"signal\":\"dependency\"}]");
	}

	public void testReference(String testSymbol, String testMethod, String type, String member, Integer args, String kind) {
		jdbc.update("""
				INSERT INTO test_reference (snapshot_id, run_id, test_symbol, test_file_path, test_method, referenced_type,
				    referenced_member, argument_count, reference_kind, line_number, confidence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 15, 'DETECTED')
				""", snapshotId, runId, testSymbol, "src/test/java/" + testSymbol.replace('.', '/') + ".java", testMethod,
				type, member, args, kind);
	}

	public void conventionTest(String testSymbol, String productionSymbol) {
		jdbc.update("""
				INSERT INTO test_record (snapshot_id, run_id, test_file_path, test_symbol, test_method_count, framework,
				    production_symbol, mapping_method, confidence, evidence)
				VALUES (?, ?, ?, ?, 2, 'JUnit 5', ?, 'NAMING_CONVENTION', 'LIKELY', CAST(? AS jsonb))
				""", snapshotId, runId, "src/test/java/" + testSymbol.replace('.', '/') + ".java", testSymbol,
				productionSymbol, "[{\"path\":\"T.java\",\"line\":3,\"signal\":\"test class\"}]");
	}

	public void testExecution(boolean withReferences) {
		jdbc.update("""
				INSERT INTO analyzer_execution (run_id, snapshot_id, analyzer_id, analyzer_version, category, status, config_summary)
				VALUES (?, ?, 'java-test-analyzer', '1.1.0', 'TESTS', 'COMPLETED', CAST(? AS jsonb))
				""", runId, snapshotId, withReferences ? "{\"testReferences\": 3}" : "{\"testClasses\": 1}");
	}

	private static String annotationJson(String names) {
		if (names == null || names.isBlank()) {
			return "[]";
		}
		return "[" + String.join(",", List.of(names.split(",")).stream().map(n -> "{\"name\":\"" + n.trim() + "\"}").toList())
				+ "]";
	}

}
