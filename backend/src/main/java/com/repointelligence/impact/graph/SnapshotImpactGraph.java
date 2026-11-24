package com.repointelligence.impact.graph;

import com.repointelligence.analysis.spi.ImpactGraph;
import com.repointelligence.common.model.Confidence;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The dependency graph of one analysis run of one snapshot, queried in PostgreSQL. Every statement filters
 * by {@code snapshot_id} and {@code run_id}, so no query can reach another commit. Traversals are
 * breadth-first recursive CTEs that keep one row per (node, depth): cycles cannot loop, work is bounded by
 * nodes times depth, and the depth and the number of returned rows are capped.
 *
 * <p>
 * Everything stays relational (plan step 5.1): measured latencies are recorded in the MVP 5 completion
 * report, and a graph database is only worth adding if they stop being acceptable.
 */
public class SnapshotImpactGraph implements ImpactGraph {

	private static final String NODE_COLUMNS = """
			s.id, s.symbol_type, s.fully_qualified_name, s.signature, s.parent_symbol_id, s.file_path, s.start_line,
			s.end_line, s.module_path,
			ARRAY(SELECT regexp_replace(a ->> 'name', '^.*[.]', '')
			      FROM jsonb_array_elements(COALESCE(s.annotations, '[]'::jsonb)) a) AS annotation_names,
			ARRAY(SELECT jsonb_array_elements_text(COALESCE(s.modifiers, '[]'::jsonb))) AS modifier_names
			""";

	private static final String TYPE_KINDS = "('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION')";

	private static final int IN_LIST_CHUNK = 5_000;

	private static final RowMapper<Node> NODE_MAPPER = SnapshotImpactGraph::node;

	private final NamedParameterJdbcTemplate jdbc;

	private final UUID snapshotId;

	private final UUID runId;

	private final int maxRows;

	private GraphStats stats;

	public SnapshotImpactGraph(NamedParameterJdbcTemplate jdbc, UUID snapshotId, UUID runId, int maxRows) {
		this.jdbc = jdbc;
		this.snapshotId = snapshotId;
		this.runId = runId;
		this.maxRows = maxRows;
	}

	private MapSqlParameterSource scope() {
		return new MapSqlParameterSource("snapshot", snapshotId).addValue("run", runId);
	}

	// ── symbols ──────────────────────────────────────────────────────────────────────────────────

	@Override
	public Optional<Node> node(UUID id) {
		return Optional.ofNullable(nodes(List.of(id)).get(id));
	}

	@Override
	public Map<UUID, Node> nodes(Collection<UUID> ids) {
		Map<UUID, Node> result = new LinkedHashMap<>();
		for (List<UUID> part : chunks(ids)) {
			jdbc.query("SELECT " + NODE_COLUMNS + " FROM code_symbol s WHERE s.snapshot_id = :snapshot AND s.run_id = :run "
					+ "AND s.id IN (:ids)", scope().addValue("ids", part), NODE_MAPPER)
				.forEach(node -> result.put(node.id(), node));
		}
		return result;
	}

	@Override
	public List<Node> membersOf(UUID typeId) {
		return jdbc.query("SELECT " + NODE_COLUMNS + " FROM code_symbol s WHERE s.snapshot_id = :snapshot AND s.run_id = :run "
				+ "AND s.parent_symbol_id = :type ORDER BY s.start_line NULLS LAST, s.fully_qualified_name, s.signature",
				scope().addValue("type", typeId), NODE_MAPPER);
	}

	@Override
	public List<Node> supertypes(UUID typeId, int maxDepth) {
		return hierarchy(typeId, maxDepth, true);
	}

	@Override
	public List<Node> subtypes(UUID typeId, int maxDepth) {
		return hierarchy(typeId, maxDepth, false);
	}

	private List<Node> hierarchy(UUID typeId, int maxDepth, boolean up) {
		String from = up ? "e.source_symbol_id" : "e.target_symbol_id";
		String to = up ? "e.target_symbol_id" : "e.source_symbol_id";
		Map<UUID, Integer> depths = new LinkedHashMap<>();
		jdbc.query("""
				WITH RECURSIVE walk(node, depth) AS (
				    SELECT CAST(:start AS uuid), 0
				    UNION
				    SELECT %s, w.depth + 1
				    FROM walk w
				    JOIN dependency_edge e ON %s = w.node
				    WHERE w.depth < :depth AND e.snapshot_id = :snapshot AND e.run_id = :run
				      AND e.relationship_type IN ('EXTENDS', 'IMPLEMENTS') AND %s IS NOT NULL
				)
				SELECT node, MIN(depth) FROM walk WHERE depth > 0 AND node <> CAST(:start AS uuid) GROUP BY node
				""".formatted(to, from, to), scope().addValue("start", typeId).addValue("depth", maxDepth),
				(RowMapper<Void>) (rs, i) -> {
					depths.put(rs.getObject(1, UUID.class), rs.getInt(2));
					return null;
				});
		return nodes(depths.keySet()).values()
			.stream()
			.sorted(java.util.Comparator.comparingInt((Node n) -> depths.get(n.id())).thenComparing(Node::fqn))
			.toList();
	}

	// ── traversals ───────────────────────────────────────────────────────────────────────────────

	@Override
	public Traversal dependents(Collection<UUID> seeds, Granularity granularity, int maxDepth) {
		if (seeds.isEmpty()) {
			return Traversal.empty();
		}
		MapSqlParameterSource params = scope().addValue("seeds", List.copyOf(seeds))
			.addValue("depth", maxDepth)
			.addValue("limit", maxRows + 1);
		// phase 1: which nodes are reached, at which shortest depth (one row per node and depth, so a hub is expanded once)
		Map<UUID, Integer> depths = new LinkedHashMap<>();
		jdbc.query(granularity == Granularity.METHOD ? methodReach() : typeReach(), params, (RowMapper<Void>) (rs, i) -> {
			depths.put(rs.getObject(1, UUID.class), rs.getInt(2));
			return null;
		});
		boolean truncated = depths.size() > maxRows;
		if (truncated) {
			depths.keySet().retainAll(List.copyOf(depths.keySet()).subList(0, maxRows));
		}
		if (depths.isEmpty()) {
			return Traversal.empty();
		}
		// phase 2: the edges between consecutive depths (who each node depends on, and where it was read)
		Map<UUID, Integer> all = new LinkedHashMap<>(depths);
		seeds.forEach(seed -> all.put(seed, 0));
		MapSqlParameterSource edgeParams = scope().addValue("sources", uuidArray(depths.keySet()))
			.addValue("targets", uuidArray(all.keySet()));
		List<Reach> rows = new ArrayList<>();
		jdbc.query(granularity == Granularity.METHOD ? methodEdges() : typeEdges(), edgeParams, (RowMapper<Void>) (rs, i) -> {
			UUID source = rs.getObject("src", UUID.class);
			UUID target = rs.getObject("dst", UUID.class);
			Integer sourceDepth = depths.get(source);
			Integer targetDepth = all.get(target);
			if (sourceDepth != null && targetDepth != null && sourceDepth == targetDepth + 1) {
				Array relationships = rs.getArray("rels");
				List<String> rels = relationships == null ? List.of() : Arrays.asList((String[]) relationships.getArray());
				rows.add(new Reach(source, sourceDepth, target, rels, rs.getString("file"), (Integer) rs.getObject("line")));
			}
			return null;
		});
		rows.sort(java.util.Comparator.comparingInt(Reach::depth)
			.thenComparing(r -> r.node().toString())
			.thenComparing(r -> r.via().toString()));
		return new Traversal(rows, truncated);
	}

	/** Postgres array literal of ids, passed as one parameter (an IN list would hit the driver's parameter limit). */
	private static String uuidArray(Collection<UUID> ids) {
		return "{" + ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")) + "}";
	}

	/**
	 * Reverse reachability over resolved CALLS edges, one row per node and depth: cycles cannot loop and the work is
	 * bounded by nodes times depth. (A first version carried the predecessor through the recursion and joined the CTE
	 * to itself: rows multiplied per predecessor and the planner chose quadratic nested loops; measured, see the
	 * MVP 5 report.)
	 */
	private static String methodReach() {
		return """
				WITH RECURSIVE reach(node, depth) AS (
				    SELECT s.id, 0 FROM code_symbol s
				    WHERE s.snapshot_id = :snapshot AND s.run_id = :run AND s.id IN (:seeds)
				    UNION
				    SELECT e.source_symbol_id, r.depth + 1
				    FROM reach r
				    JOIN dependency_edge e ON e.target_symbol_id = r.node
				    WHERE r.depth < :depth AND e.snapshot_id = :snapshot AND e.run_id = :run
				      AND e.relationship_type = 'CALLS'
				)
				SELECT node, MIN(depth) AS depth FROM reach
				WHERE depth > 0 AND node NOT IN (SELECT node FROM reach WHERE depth = 0)
				GROUP BY node ORDER BY MIN(depth), node LIMIT :limit
				""";
	}

	private static String methodEdges() {
		return """
				WITH wanted_targets AS MATERIALIZED (SELECT DISTINCT unnest(CAST(:targets AS uuid[])) AS id),
				     wanted_sources AS MATERIALIZED (SELECT DISTINCT unnest(CAST(:sources AS uuid[])) AS id)
				SELECT e.source_symbol_id AS src, e.target_symbol_id AS dst, ARRAY[e.relationship_type] AS rels,
				       e.evidence ->> 'file' AS file, CAST(e.evidence ->> 'line' AS integer) AS line
				FROM wanted_targets wt
				JOIN dependency_edge e ON e.target_symbol_id = wt.id
				JOIN wanted_sources ws ON ws.id = e.source_symbol_id
				WHERE e.snapshot_id = :snapshot AND e.run_id = :run AND e.relationship_type = 'CALLS'
				""";
	}

	/**
	 * The same over types: every resolved relationship except imports, from members to their enclosing type. The
	 * lateral subquery finds, through indexes, the types with an edge to the reached type or to one of its members.
	 */
	private static String typeReach() {
		return """
				WITH RECURSIVE reach(node, depth) AS (
				    SELECT s.id, 0 FROM code_symbol s
				    WHERE s.snapshot_id = :snapshot AND s.run_id = :run AND s.id IN (:seeds)
				    UNION
				    SELECT d.src, r.depth + 1
				    FROM reach r
				    CROSS JOIN LATERAL (
				        SELECT DISTINCT x.src FROM (
				            SELECT CASE WHEN s.symbol_type IN %1$s THEN s.id ELSE s.parent_symbol_id END AS src
				            FROM dependency_edge e JOIN code_symbol s ON s.id = e.source_symbol_id
				            WHERE e.target_symbol_id = r.node AND e.snapshot_id = :snapshot AND e.run_id = :run
				              AND e.relationship_type <> 'IMPORTS'
				            UNION ALL
				            SELECT CASE WHEN s.symbol_type IN %1$s THEN s.id ELSE s.parent_symbol_id END
				            FROM code_symbol m
				            JOIN dependency_edge e ON e.target_symbol_id = m.id
				            JOIN code_symbol s ON s.id = e.source_symbol_id
				            WHERE m.parent_symbol_id = r.node AND e.snapshot_id = :snapshot AND e.run_id = :run
				              AND e.relationship_type <> 'IMPORTS'
				        ) x WHERE x.src IS NOT NULL AND x.src <> r.node
				    ) d
				    WHERE r.depth < :depth
				)
				SELECT node, MIN(depth) AS depth FROM reach
				WHERE depth > 0 AND node NOT IN (SELECT node FROM reach WHERE depth = 0)
				GROUP BY node ORDER BY MIN(depth), node LIMIT :limit
				""".formatted(TYPE_KINDS);
	}

	private static String typeEdges() {
		return """
				WITH wanted_targets AS MATERIALIZED (SELECT DISTINCT unnest(CAST(:targets AS uuid[])) AS id),
				     wanted_sources AS MATERIALIZED (SELECT DISTINCT unnest(CAST(:sources AS uuid[])) AS id),
				     target_symbols AS MATERIALIZED (
				         SELECT m.id AS symbol_id, wt.id AS dst FROM wanted_targets wt JOIN code_symbol m ON m.id = wt.id
				         UNION ALL
				         SELECT m.id, wt.id FROM wanted_targets wt JOIN code_symbol m ON m.parent_symbol_id = wt.id
				     )
				SELECT x.src, x.dst, array_agg(DISTINCT x.rel ORDER BY x.rel) AS rels,
				       (array_agg(x.file ORDER BY x.line NULLS LAST))[1] AS file, MIN(x.line) AS line
				FROM (
				    SELECT CASE WHEN s.symbol_type IN %1$s THEN s.id ELSE s.parent_symbol_id END AS src, ts.dst,
				           e.relationship_type AS rel, e.evidence ->> 'file' AS file, CAST(e.evidence ->> 'line' AS integer) AS line
				    FROM target_symbols ts
				    JOIN dependency_edge e ON e.target_symbol_id = ts.symbol_id
				    JOIN code_symbol s ON s.id = e.source_symbol_id
				    WHERE e.snapshot_id = :snapshot AND e.run_id = :run AND e.relationship_type <> 'IMPORTS'
				) x
				JOIN wanted_sources ws ON ws.id = x.src
				WHERE x.src <> x.dst
				GROUP BY x.src, x.dst
				""".formatted(TYPE_KINDS);
	}

	@Override
	public List<DependencyOf> directDependencies(Collection<UUID> sourceIds, boolean includeMembers) {
		Set<DependencyOf> result = new LinkedHashSet<>();
		String sourceFilter = includeMembers ? "(e.source_symbol_id IN (:ids) OR s.parent_symbol_id IN (:ids))"
				: "e.source_symbol_id IN (:ids)";
		for (List<UUID> part : chunks(sourceIds)) {
			result.addAll(jdbc.query("""
					SELECT DISTINCT CASE WHEN s.symbol_type IN %1$s THEN s.id ELSE s.parent_symbol_id END AS source_type,
					       CASE WHEN t.symbol_type IN %1$s THEN t.id ELSE t.parent_symbol_id END AS target_type,
					       e.relationship_type, e.source_symbol_id
					FROM dependency_edge e
					JOIN code_symbol s ON s.id = e.source_symbol_id
					JOIN code_symbol t ON t.id = e.target_symbol_id
					WHERE e.snapshot_id = :snapshot AND e.run_id = :run AND e.target_symbol_id IS NOT NULL
					  AND e.relationship_type <> 'IMPORTS' AND %2$s
					ORDER BY 2, 3, 4
					""".formatted(TYPE_KINDS, sourceFilter), scope().addValue("ids", part), (rs, i) -> {
				UUID target = rs.getObject("target_type", UUID.class);
				UUID sourceType = rs.getObject("source_type", UUID.class);
				return target == null || target.equals(sourceType) ? null
						: new DependencyOf(rs.getObject("source_symbol_id", UUID.class), target, rs.getString("relationship_type"));
			}).stream().filter(java.util.Objects::nonNull).toList());
		}
		return List.copyOf(result);
	}

	@Override
	public List<AmbiguousCaller> ambiguousCallers(Node method) {
		if (!method.isCallable() || method.signature() == null || !method.signature().contains("(")) {
			return List.of();
		}
		String name = method.type().equals("CONSTRUCTOR") ? "<init>" : method.simpleName();
		String candidate = method.ownerName() + "#" + name + method.signature().substring(method.signature().indexOf('('));
		return jdbc.query("""
				SELECT e.source_symbol_id, e.evidence ->> 'file' AS file, CAST(e.evidence ->> 'line' AS integer) AS line
				FROM dependency_edge e
				WHERE e.snapshot_id = :snapshot AND e.run_id = :run AND e.relationship_type = 'CALLS'
				  AND e.resolution = 'AMBIGUOUS' AND jsonb_exists(e.evidence -> 'candidates', :candidate)
				ORDER BY e.source_symbol_id
				""", scope().addValue("candidate", candidate),
				(rs, i) -> new AmbiguousCaller(rs.getObject(1, UUID.class), rs.getString("file"), (Integer) rs.getObject("line")));
	}

	// ── architecture facts ───────────────────────────────────────────────────────────────────────

	@Override
	public Map<UUID, LayerOf> layers(Collection<UUID> symbolIds) {
		Map<UUID, LayerOf> result = new LinkedHashMap<>();
		for (List<UUID> part : chunks(symbolIds)) {
			jdbc.query("SELECT symbol_id, layer, confidence FROM layer_assignment WHERE snapshot_id = :snapshot "
					+ "AND run_id = :run AND symbol_id IN (:ids)", scope().addValue("ids", part),
					(rs, i) -> new LayerOf(rs.getObject(1, UUID.class), rs.getString(2),
							Confidence.valueOf(rs.getString(3))))
				.forEach(layer -> result.put(layer.symbolId(), layer));
		}
		return result;
	}

	@Override
	public List<EndpointOf> endpoints(Collection<UUID> controllerOrHandlerIds) {
		Set<EndpointOf> result = new LinkedHashSet<>();
		for (List<UUID> part : chunks(controllerOrHandlerIds)) {
			result.addAll(jdbc.query("""
					SELECT http_method, route, route_resolved, controller_symbol_id, handler_symbol_id, file_path, line_number
					FROM api_endpoint
					WHERE snapshot_id = :snapshot AND run_id = :run
					  AND (controller_symbol_id IN (:ids) OR handler_symbol_id IN (:ids))
					ORDER BY route, http_method, handler_symbol_id
					""", scope().addValue("ids", part),
					(rs, i) -> new EndpointOf(rs.getString(1), rs.getString(2), rs.getBoolean(3),
							rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6),
							(Integer) rs.getObject(7))));
		}
		return List.copyOf(result);
	}

	@Override
	public List<EntityLinkOf> entityLinks(Collection<UUID> typeIds) {
		Set<EntityLinkOf> result = new LinkedHashSet<>();
		for (List<UUID> part : chunks(typeIds)) {
			result.addAll(jdbc.query("""
					SELECT source_symbol_id, target_symbol_id, relationship_type, field_name
					FROM entity_relationship
					WHERE snapshot_id = :snapshot AND run_id = :run AND target_symbol_id IS NOT NULL
					  AND (source_symbol_id IN (:ids) OR target_symbol_id IN (:ids))
					ORDER BY relationship_type, source_symbol_id, target_symbol_id, field_name
					""", scope().addValue("ids", part),
					(rs, i) -> new EntityLinkOf(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
							rs.getString(3), rs.getString(4))));
		}
		return List.copyOf(result);
	}

	@Override
	public List<ModuleLink> moduleDependents(String modulePath, int maxDepth) {
		if (modulePath == null) {
			return List.of();
		}
		MapSqlParameterSource params = scope().addValue("module", modulePath).addValue("depth", maxDepth);
		Map<String, Integer> depths = new LinkedHashMap<>();
		jdbc.query("""
				WITH RECURSIVE walk(module, depth) AS (
				    SELECT CAST(:module AS varchar), 0
				    UNION
				    SELECT md.source_module_path, w.depth + 1
				    FROM walk w
				    JOIN module_dependency md ON md.target_module_path = w.module
				    WHERE w.depth < :depth AND md.snapshot_id = :snapshot AND md.run_id = :run
				)
				SELECT module, MIN(depth) FROM walk WHERE depth > 0 AND module <> :module GROUP BY module
				ORDER BY MIN(depth), module
				""", params, (RowMapper<Void>) (rs, i) -> {
			depths.put(rs.getString(1), rs.getInt(2));
			return null;
		});
		if (depths.isEmpty()) {
			return List.of();
		}
		Map<String, List<String>> evidence = new LinkedHashMap<>();
		jdbc.query("""
				SELECT source_module_path, target_module_path,
				       ARRAY(SELECT e ->> 'path' FROM jsonb_array_elements(COALESCE(evidence, '[]'::jsonb)) e) AS paths
				FROM module_dependency
				WHERE snapshot_id = :snapshot AND run_id = :run AND source_module_path IN (:modules)
				ORDER BY source_module_path, target_module_path
				""", scope().addValue("modules", List.copyOf(depths.keySet())), (RowMapper<Void>) (rs, i) -> {
			String source = rs.getString(1);
			String target = rs.getString(2);
			boolean closer = target.equals(modulePath) || depths.getOrDefault(target, Integer.MAX_VALUE) < depths.get(source);
			if (closer) {
				String[] paths = (String[]) rs.getArray(3).getArray();
				evidence.computeIfAbsent(source, k -> new ArrayList<>())
					.addAll(Arrays.stream(paths).filter(java.util.Objects::nonNull).toList());
			}
			return null;
		});
		List<ModuleLink> result = new ArrayList<>();
		depths.forEach((module, depth) -> result.add(new ModuleLink(module, modulePath, depth,
				evidence.getOrDefault(module, List.of()).stream().distinct().sorted().toList())));
		return result;
	}

	// ── tests ────────────────────────────────────────────────────────────────────────────────────

	@Override
	public List<TestReferenceOf> testReferences(Collection<String> typeFqns) {
		List<TestReferenceOf> result = new ArrayList<>();
		for (List<String> part : chunks(typeFqns)) {
			result.addAll(jdbc.query("""
					SELECT test_symbol, test_file_path, test_method, referenced_type, referenced_member, argument_count,
					       reference_kind, line_number, confidence
					FROM test_reference
					WHERE snapshot_id = :snapshot AND run_id = :run AND referenced_type IN (:types)
					ORDER BY test_symbol, referenced_type, reference_kind, referenced_member, test_method, line_number
					""", scope().addValue("types", part),
					(rs, i) -> new TestReferenceOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getString(5), (Integer) rs.getObject(6), rs.getString(7), (Integer) rs.getObject(8),
							Confidence.valueOf(rs.getString(9)))));
		}
		return result;
	}

	@Override
	public List<ConventionTest> conventionTests(Collection<String> typeFqns) {
		List<ConventionTest> result = new ArrayList<>();
		for (List<String> part : chunks(typeFqns)) {
			result.addAll(jdbc.query("""
					SELECT test_symbol, test_file_path, production_symbol,
					       CAST(evidence -> 0 ->> 'line' AS integer) AS line
					FROM test_record
					WHERE snapshot_id = :snapshot AND run_id = :run AND test_symbol IS NOT NULL
					  AND production_symbol IN (:types)
					ORDER BY test_symbol
					""", scope().addValue("types", part),
					(rs, i) -> new ConventionTest(rs.getString(1), rs.getString(2), rs.getString(3),
							(Integer) rs.getObject(4))));
		}
		return result;
	}

	// ── completeness ─────────────────────────────────────────────────────────────────────────────

	@Override
	public GraphStats stats() {
		if (stats == null) {
			MapSqlParameterSource scope = scope();
			long symbols = count("SELECT count(*) FROM code_symbol WHERE snapshot_id = :snapshot AND run_id = :run", scope);
			long edges = count("SELECT count(*) FROM dependency_edge WHERE snapshot_id = :snapshot AND run_id = :run", scope);
			long unresolved = count("SELECT count(*) FROM dependency_edge WHERE snapshot_id = :snapshot AND run_id = :run "
					+ "AND resolution = 'UNRESOLVED'", scope);
			long ambiguous = count("SELECT count(*) FROM dependency_edge WHERE snapshot_id = :snapshot AND run_id = :run "
					+ "AND resolution = 'AMBIGUOUS'", scope);
			long notResolvable = count("""
					SELECT COALESCE(SUM(CAST(config_summary ->> 'callsNotResolvable' AS bigint)), 0)
					FROM analyzer_execution WHERE run_id = :run AND analyzer_id = 'spring-architecture-analyzer'
					""", scope);
			long testsRan = count("""
					SELECT count(*) FROM analyzer_execution WHERE run_id = :run AND analyzer_id = 'java-test-analyzer'
					  AND status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
					""", scope);
			long withReferences = count("""
					SELECT count(*) FROM analyzer_execution WHERE run_id = :run AND analyzer_id = 'java-test-analyzer'
					  AND status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS') AND jsonb_exists(config_summary, 'testReferences')
					""", scope);
			stats = new GraphStats(symbols, edges, unresolved, ambiguous, notResolvable, withReferences > 0, testsRan > 0);
		}
		return stats;
	}

	private long count(String sql, MapSqlParameterSource params) {
		Long value = jdbc.queryForObject(sql, params, Long.class);
		return value == null ? 0 : value;
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private static <T> List<List<T>> chunks(Collection<T> values) {
		List<T> all = new ArrayList<>(values);
		List<List<T>> result = new ArrayList<>();
		for (int i = 0; i < all.size(); i += IN_LIST_CHUNK) {
			result.add(all.subList(i, Math.min(all.size(), i + IN_LIST_CHUNK)));
		}
		return result;
	}

	private static Node node(ResultSet rs, int row) throws SQLException {
		return new Node(rs.getObject("id", UUID.class), rs.getString("symbol_type"), rs.getString("fully_qualified_name"),
				rs.getString("signature"), rs.getObject("parent_symbol_id", UUID.class), rs.getString("file_path"),
				(Integer) rs.getObject("start_line"), (Integer) rs.getObject("end_line"),
				strings(rs.getArray("annotation_names")), strings(rs.getArray("modifier_names")),
				rs.getString("module_path"));
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
	}

}
