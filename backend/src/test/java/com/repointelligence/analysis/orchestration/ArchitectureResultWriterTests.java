package com.repointelligence.analysis.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.packs.java.SpringArchitectureAnalyzer;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.ArchitectureResult;
import com.repointelligence.analysis.spi.ArchitectureResult.Edge;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.InMemoryWorkspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Architecture results are stored by the orchestrator's writer: keys become ids, nothing is stored half-linked. */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest(properties = "analysis.jobs.enabled=false")
class ArchitectureResultWriterTests {

	@Autowired
	private AnalysisResultWriter writer;

	@Autowired
	private JdbcTemplate jdbc;

	private final SpringArchitectureAnalyzer analyzer = new SpringArchitectureAnalyzer();

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	@Test
	void theFixtureGraphIsStoredWithResolvedEdgesPointingAtStoredSymbols() {
		UUID[] scope = newRun();
		AnalyzerResult result = analyzer.analyze(InMemoryWorkspace.fromFixture("spring-boot-architecture").context());
		ArchitectureResult architecture = result.architecture();

		writer.saveCompleted(scope[0], scope[1], analyzer.descriptor(), Instant.now(), Instant.now(), result);

		assertThat(count("code_symbol", scope[1])).isEqualTo(architecture.symbols().size());
		assertThat(count("dependency_edge", scope[1])).isEqualTo(architecture.edges().size());
		// the controller's injection edge points at the stored service symbol
		String injected = jdbc.queryForObject("""
				SELECT t.fully_qualified_name FROM dependency_edge e
				JOIN code_symbol s ON s.id = e.source_symbol_id
				JOIN code_symbol t ON t.id = e.target_symbol_id
				WHERE e.run_id = ? AND e.relationship_type = 'INJECTS'
				  AND s.fully_qualified_name = 'com.example.shop.web.OrderController'
				""", String.class, scope[1]);
		assertThat(injected).isEqualTo("com.example.shop.service.OrderService");
		// members hang under their type
		assertThat(jdbc.queryForObject("""
				SELECT p.fully_qualified_name FROM code_symbol m JOIN code_symbol p ON p.id = m.parent_symbol_id
				WHERE m.run_id = ? AND m.fully_qualified_name = 'com.example.shop.web.OrderController#list'
				""", String.class, scope[1])).isEqualTo("com.example.shop.web.OrderController");
		// annotations keep literal attributes
		assertThat(jdbc.queryForObject("""
				SELECT annotations -> 0 ->> 'name' FROM code_symbol
				WHERE run_id = ? AND fully_qualified_name = 'com.example.shop.domain.Order'
				""", String.class, scope[1])).isEqualTo("Entity");
		// unresolved edges keep their name and have no target
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM dependency_edge WHERE run_id = ? AND resolution = 'UNRESOLVED'
				  AND (target_symbol_id IS NOT NULL OR target_unresolved_name IS NULL)
				""", Long.class, scope[1])).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM analyzer_execution WHERE run_id = ?", String.class, scope[1]))
			.isEqualTo("COMPLETED");
	}

	@Test
	void anEdgeNamingAnUnknownSymbolIsDroppedAndRecordedAsALimitation() {
		UUID[] scope = newRun();
		AnalyzerResult analyzed = analyzer.analyze(new InMemoryWorkspace()
			.add("src/main/java/demo/A.java", "package demo;\npublic class A {}\n")
			.context());
		ArchitectureResult original = analyzed.architecture();
		List<Edge> edges = new ArrayList<>(original.edges());
		String a = original.symbols().get(0).key();
		edges.add(new Edge(a, "no-such-key", "demo.Missing", RelationshipType.CALLS, EdgeResolution.RESOLVED,
				Map.of("file", "src/main/java/demo/A.java", "line", 1)));
		edges.add(new Edge("no-such-source", null, "demo.Other", RelationshipType.CALLS, EdgeResolution.UNRESOLVED,
				Map.of()));
		AnalyzerResult result = AnalyzerResult.builder()
			.architecture(new ArchitectureResult(original.symbols(), edges, null, null, null, null, null))
			.build();

		writer.saveCompleted(scope[0], scope[1], analyzer.descriptor(), Instant.now(), Instant.now(), result);

		assertThat(count("code_symbol", scope[1])).isEqualTo(1);
		assertThat(count("dependency_edge", scope[1])).isEqualTo(original.edges().size());
		assertThat(jdbc.queryForObject("SELECT limitations::text FROM analyzer_execution WHERE run_id = ?", String.class,
				scope[1])).contains("2 architecture items referred to symbols that were not stored");
	}

	private long count(String table, UUID runId) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE run_id = ?", Long.class, runId);
	}

	private UUID[] newRun() {
		UUID repository = jdbc.queryForObject("INSERT INTO repository (owner, name, canonical_url) VALUES (?, ?, ?) RETURNING id",
				UUID.class, "acme", "shop-" + UUID.randomUUID(), "https://github.com/acme/shop");
		UUID snapshot = jdbc.queryForObject(
				"INSERT INTO repository_snapshot (repository_id, commit_sha) VALUES (?, ?) RETURNING id", UUID.class,
				repository, "d".repeat(40));
		UUID run = jdbc.queryForObject("INSERT INTO analysis_run (snapshot_id, status) VALUES (?, 'ANALYZING') RETURNING id",
				UUID.class, snapshot);
		return new UUID[] { snapshot, run };
	}

}
