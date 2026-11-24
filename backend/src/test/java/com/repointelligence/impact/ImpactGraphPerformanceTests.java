package com.repointelligence.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.spi.ImpactGraph.Granularity;
import com.repointelligence.analysis.spi.ImpactGraph.Traversal;
import com.repointelligence.impact.graph.SnapshotImpactGraph;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.GraphSeeder;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Step 5.1 performance gate: traversals on a synthetic repository of the size the plan calls realistic (a
 * few thousand types, tens of thousands of methods and edges) must stay inside an agreed latency budget in
 * plain relational PostgreSQL. A graph database is only worth adding if this stops holding. The measured
 * times are written to {@code target/impact-performance.txt} and recorded in the MVP 5 completion report.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class ImpactGraphPerformanceTests {

	private static final int TYPES = 2_000;

	private static final int METHODS_PER_TYPE = 10;

	private static final int CALLS_PER_METHOD = 3;

	/**
	 * Worst case: a hub that (transitively) most of the repository depends on, so the traversal reaches almost every method.
	 * Generous on purpose: the budget protects against a missing index or a runaway query (the first version took minutes),
	 * not against a slow CI disk.
	 */
	private static final long BUDGET_MS = 12_000;

	/** A typical target (an ordinary method with a handful of dependents) must answer interactively. */
	private static final long TYPICAL_BUDGET_MS = 2_000;

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NamedParameterJdbcTemplate named;

	@Autowired
	private PlatformTransactionManager transactions;

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	@Test
	void traversalsOnAMediumRepositoryStayInsideTheLatencyBudget() throws Exception {
		GraphSeeder seed = GraphSeeder.create(em, transactions, jdbc);
		Random random = new Random(42);
		List<UUID> typeIds = new ArrayList<>();
		List<UUID> methodIds = new ArrayList<>();
		List<Object[]> symbols = new ArrayList<>();
		for (int t = 0; t < TYPES; t++) {
			UUID type = UUID.randomUUID();
			typeIds.add(type);
			String fqn = "perf.pkg" + (t / 50) + ".Type" + t;
			symbols.add(new Object[] { type, "CLASS", fqn, null, null, fqn.replace('.', '/') + ".java" });
			for (int m = 0; m < METHODS_PER_TYPE; m++) {
				UUID method = UUID.randomUUID();
				methodIds.add(method);
				symbols.add(new Object[] { method, "METHOD", fqn + "#m" + m, "m" + m + "(int)", type,
						fqn.replace('.', '/') + ".java" });
			}
		}
		jdbc.batchUpdate("""
				INSERT INTO code_symbol (id, snapshot_id, run_id, symbol_type, fully_qualified_name, signature,
				    parent_symbol_id, file_path, start_line, end_line)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 10)
				""", symbols, 2_000, (ps, row) -> {
			ps.setObject(1, row[0]);
			ps.setObject(2, seed.snapshotId());
			ps.setObject(3, seed.runId());
			ps.setString(4, (String) row[1]);
			ps.setString(5, (String) row[2]);
			ps.setString(6, (String) row[3]);
			ps.setObject(7, row[4]);
			ps.setString(8, (String) row[5]);
		});
		// calls skewed towards low indexes, so a few methods are hubs with many direct and indirect callers
		List<Object[]> edges = new ArrayList<>();
		for (UUID method : methodIds) {
			for (int c = 0; c < CALLS_PER_METHOD; c++) {
				double r = random.nextDouble();
				UUID target = methodIds.get((int) (methodIds.size() * r * r * r));
				edges.add(new Object[] { method, target, "CALLS" });
			}
		}
		for (UUID type : typeIds) {
			for (int i = 0; i < 2; i++) {
				double r = random.nextDouble();
				edges.add(new Object[] { type, typeIds.get((int) (typeIds.size() * r * r)), "INJECTS" });
			}
		}
		jdbc.batchUpdate("""
				INSERT INTO dependency_edge (snapshot_id, run_id, source_symbol_id, target_symbol_id, relationship_type,
				    resolution, evidence)
				VALUES (?, ?, ?, ?, ?, 'RESOLVED', CAST('{"file":"F.java","line":3}' AS jsonb))
				""", edges, 5_000, (ps, row) -> {
			ps.setObject(1, seed.snapshotId());
			ps.setObject(2, seed.runId());
			ps.setObject(3, row[0]);
			ps.setObject(4, row[1]);
			ps.setString(5, (String) row[2]);
		});
		jdbc.execute("ANALYZE code_symbol");
		jdbc.execute("ANALYZE dependency_edge");

		SnapshotImpactGraph graph = new SnapshotImpactGraph(named, seed.snapshotId(), seed.runId(), 20_000);
		StringBuilder report = new StringBuilder("symbols=%d edges=%d%n".formatted(symbols.size(), edges.size()));
		long slowest = 0;
		for (int depth : new int[] { 3, 5 }) {
			for (Granularity granularity : Granularity.values()) {
				UUID hub = granularity == Granularity.METHOD ? methodIds.get(0) : typeIds.get(0);
				graph.dependents(List.of(hub), granularity, depth); // warm-up
				long started = System.nanoTime();
				Traversal traversal = graph.dependents(List.of(hub), granularity, depth);
				long ms = (System.nanoTime() - started) / 1_000_000;
				report.append("%s depth=%d reached=%d truncated=%b ms=%d%n".formatted(granularity, depth,
						traversal.reached().size(), traversal.truncated(), ms));
				assertThat(traversal.reached()).isNotEmpty();
				slowest = Math.max(slowest, ms);
			}
		}
		UUID typical = methodIds.get(methodIds.size() / 2);
		for (Granularity granularity : Granularity.values()) {
			UUID start = granularity == Granularity.METHOD ? typical : typeIds.get(typeIds.size() / 2);
			graph.dependents(List.of(start), granularity, 5);
			long started = System.nanoTime();
			Traversal traversal = graph.dependents(List.of(start), granularity, 5);
			long ms = (System.nanoTime() - started) / 1_000_000;
			report.append("typical %s depth=5 reached=%d ms=%d%n".formatted(granularity, traversal.reached().size(), ms));
			assertThat(ms).as("typical %s traversal", granularity).isLessThan(TYPICAL_BUDGET_MS);
		}
		Files.writeString(Path.of("target/impact-performance.txt"), report.toString());
		System.out.println(report);
		assertThat(slowest).as("slowest traversal (ms), see target/impact-performance.txt").isLessThan(BUDGET_MS);
	}

}
