package com.repointelligence.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.spi.ImpactGraph;
import com.repointelligence.analysis.spi.ImpactGraph.Granularity;
import com.repointelligence.analysis.spi.ImpactGraph.Reach;
import com.repointelligence.analysis.spi.ImpactGraph.Traversal;
import com.repointelligence.impact.graph.SnapshotImpactGraph;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.GraphSeeder;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Step 5.1: reverse traversals, hierarchy and scoping of the relational dependency graph (PostgreSQL). */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class SnapshotImpactGraphTests {

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

	private GraphSeeder seeder() {
		return GraphSeeder.create(em, transactions, jdbc);
	}

	private ImpactGraph graph(GraphSeeder seed) {
		return new SnapshotImpactGraph(named, seed.snapshotId(), seed.runId(), 20_000);
	}

	private static Map<UUID, Integer> depths(Traversal traversal) {
		return traversal.reached().stream().collect(Collectors.toMap(Reach::node, Reach::depth, Math::min));
	}

	/** a() -> b() -> c() -> d(): callers of d, with the depth limit respected. */
	@Test
	void callersAreFoundToTheRequestedDepth() {
		GraphSeeder seed = seeder();
		seed.clazz("p.A");
		seed.clazz("p.B");
		seed.clazz("p.C");
		seed.clazz("p.D");
		UUID a = seed.method("p.A", "a", "");
		UUID b = seed.method("p.B", "b", "");
		UUID c = seed.method("p.C", "c", "");
		UUID d = seed.method("p.D", "d", "");
		seed.edge(a, b, "CALLS");
		seed.edge(b, c, "CALLS");
		seed.edge(c, d, "CALLS");

		Traversal two = graph(seed).dependents(List.of(d), Granularity.METHOD, 2);
		Traversal three = graph(seed).dependents(List.of(d), Granularity.METHOD, 3);

		assertThat(depths(two)).containsOnly(Map.entry(c, 1), Map.entry(b, 2));
		assertThat(depths(three)).containsOnly(Map.entry(c, 1), Map.entry(b, 2), Map.entry(a, 3));
		assertThat(three.truncated()).isFalse();
		Reach direct = three.reached().stream().filter(r -> r.node().equals(c)).findFirst().orElseThrow();
		assertThat(direct.via()).isEqualTo(d);
		assertThat(direct.relationships()).containsExactly("CALLS");
		assertThat(direct.file()).isEqualTo("A.java");
		assertThat(direct.line()).isEqualTo(7);
	}

	/** x <-> y call each other and both reach z: no loop, each node once at its shortest depth, the target is never its own dependent. */
	@Test
	void cyclesDoNotLoopAndShortestDepthWins() {
		GraphSeeder seed = seeder();
		seed.clazz("p.X");
		seed.clazz("p.Y");
		seed.clazz("p.Z");
		UUID x = seed.method("p.X", "x", "");
		UUID y = seed.method("p.Y", "y", "");
		UUID z = seed.method("p.Z", "z", "");
		seed.edge(x, y, "CALLS");
		seed.edge(y, x, "CALLS");
		seed.edge(x, z, "CALLS");
		seed.edge(y, z, "CALLS");
		seed.edge(z, z, "CALLS"); // recursion

		Traversal traversal = graph(seed).dependents(List.of(z), Granularity.METHOD, 6);

		assertThat(depths(traversal)).containsOnly(Map.entry(x, 1), Map.entry(y, 1));
		assertThat(traversal.reached()).extracting(Reach::node).doesNotContain(z);
		assertThat(traversal.reached().size()).isLessThanOrEqualTo(4);
	}

	@Test
	void typeTraversalLiftsMembersAndMergesRelationships() {
		GraphSeeder seed = seeder();
		UUID controller = seed.clazz("p.Controller");
		UUID service = seed.clazz("p.Service");
		UUID repo = seed.clazz("p.Repo");
		UUID handler = seed.method("p.Controller", "handle", "");
		UUID work = seed.method("p.Service", "work", "");
		UUID save = seed.method("p.Repo", "save", "");
		seed.edge(controller, service, "INJECTS");
		seed.edge(handler, work, "CALLS");
		seed.edge(service, repo, "INJECTS");
		seed.edge(work, save, "CALLS");

		Traversal traversal = graph(seed).dependents(List.of(repo), Granularity.TYPE, 3);

		assertThat(depths(traversal)).containsOnly(Map.entry(service, 1), Map.entry(controller, 2));
		Reach viaService = traversal.reached().stream().filter(r -> r.node().equals(service)).findFirst().orElseThrow();
		assertThat(viaService.relationships()).containsExactly("CALLS", "INJECTS");
		Reach viaController = traversal.reached().stream().filter(r -> r.node().equals(controller)).findFirst().orElseThrow();
		assertThat(viaController.via()).isEqualTo(service);
	}

	@Test
	void typeCyclesTerminate() {
		GraphSeeder seed = seeder();
		UUID a = seed.clazz("p.A");
		UUID b = seed.clazz("p.B");
		UUID c = seed.clazz("p.C");
		seed.edge(a, b, "INJECTS");
		seed.edge(b, a, "INJECTS");
		seed.edge(b, c, "FIELD_TYPE");

		Traversal traversal = graph(seed).dependents(List.of(c), Granularity.TYPE, 8);

		assertThat(depths(traversal)).containsOnly(Map.entry(b, 1), Map.entry(a, 2));
	}

	@Test
	void importsAreNotDependenciesAndUnresolvedEdgesAreNeverFollowed() {
		GraphSeeder seed = seeder();
		UUID a = seed.clazz("p.A");
		UUID b = seed.clazz("p.B");
		seed.edge(a, b, "IMPORTS");
		UUID am = seed.method("p.A", "m", "");
		seed.ambiguousCall(am, "p.B#m()");

		assertThat(graph(seed).dependents(List.of(b), Granularity.TYPE, 3).reached()).isEmpty();
		assertThat(graph(seed).dependents(List.of(seed.method("p.B", "m", "")), Granularity.METHOD, 3).reached()).isEmpty();
	}

	@Test
	void ambiguousCallsNameTheirCandidates() {
		GraphSeeder seed = seeder();
		seed.clazz("p.A");
		seed.clazz("p.B");
		UUID caller = seed.method("p.A", "go", "");
		UUID target = seed.method("p.B", "m", "int, String");
		seed.method("p.B", "m", "long");
		seed.ambiguousCall(caller, "p.B#m(int, String)", "p.B#m(long)");

		ImpactGraph graph = graph(seed);
		ImpactGraph.Node node = graph.node(target).orElseThrow();

		assertThat(graph.ambiguousCallers(node)).extracting(ImpactGraph.AmbiguousCaller::callerId).containsExactly(caller);
		assertThat(node.parameterCount()).isEqualTo(2);
		assertThat(node.simpleName()).isEqualTo("m");
		assertThat(node.ownerName()).isEqualTo("p.B");
	}

	@Test
	void hierarchyIsTransitiveAndCycleSafe() {
		GraphSeeder seed = seeder();
		UUID base = seed.clazz("p.Base");
		UUID mid = seed.clazz("p.Mid");
		UUID leaf = seed.clazz("p.Leaf");
		UUID port = seed.clazz("p.Port");
		seed.edge(mid, base, "EXTENDS");
		seed.edge(leaf, mid, "EXTENDS");
		seed.edge(leaf, port, "IMPLEMENTS");
		seed.edge(base, leaf, "EXTENDS"); // a broken hierarchy must not loop

		assertThat(graph(seed).supertypes(leaf, 5)).extracting(ImpactGraph.Node::fqn).containsExactly("p.Mid", "p.Port", "p.Base");
		assertThat(graph(seed).subtypes(base, 5)).extracting(ImpactGraph.Node::fqn).containsExactly("p.Mid", "p.Leaf");
	}

	@Test
	void aTraversalNeverLeavesItsSnapshot() {
		GraphSeeder one = seeder();
		GraphSeeder two = seeder();
		for (GraphSeeder seed : List.of(one, two)) {
			seed.clazz("p.A");
			seed.clazz("p.B");
			seed.edge(seed.method("p.A", "a", ""), seed.method("p.B", "b", ""), "CALLS");
		}
		UUID targetInOne = one.id("p.B#b()");

		Traversal inOne = graph(one).dependents(List.of(targetInOne), Granularity.METHOD, 3);
		Traversal asked = graph(two).dependents(List.of(targetInOne), Granularity.METHOD, 3);

		assertThat(inOne.reached()).hasSize(1);
		assertThat(inOne.reached().get(0).node()).isEqualTo(one.id("p.A#a()"));
		assertThat(asked.reached()).as("a symbol id of another snapshot is not a seed here").isEmpty();
		assertThat(graph(two).node(targetInOne)).isEmpty();
	}

	@Test
	void largeResultsAreCappedAndSaySo() {
		GraphSeeder seed = seeder();
		seed.clazz("p.Hub");
		UUID target = seed.method("p.Hub", "target", "");
		for (int i = 0; i < 12; i++) {
			seed.clazz("p.Caller" + i);
			seed.edge(seed.method("p.Caller" + i, "call", ""), target, "CALLS");
		}

		Traversal capped = new SnapshotImpactGraph(named, seed.snapshotId(), seed.runId(), 5).dependents(List.of(target),
				Granularity.METHOD, 2);

		assertThat(capped.reached()).hasSize(5);
		assertThat(capped.truncated()).isTrue();
	}

	@Test
	void modulesThatDependOnAModuleAreFoundTransitively() {
		GraphSeeder seed = seeder();
		seed.moduleDependency("web", "core");
		seed.moduleDependency("api", "web");
		seed.moduleDependency("core", "api"); // a cycle between modules

		List<ImpactGraph.ModuleLink> links = graph(seed).moduleDependents("core", 5);

		assertThat(links).extracting(ImpactGraph.ModuleLink::sourceModule).containsExactly("web", "api");
		assertThat(links).extracting(ImpactGraph.ModuleLink::depth).containsExactly(1, 2);
		assertThat(links.get(0).evidencePaths()).containsExactly("web/pom.xml");
	}

	@Test
	void testReferencesAndCompletenessAreReported() {
		GraphSeeder seed = seeder();
		seed.clazz("p.A");
		seed.testReference("p.ATests", "works", "p.A", "go", 1, "CALL");
		seed.testReference("p.ATests", null, "p.A", null, null, "TYPE_USE");
		seed.conventionTest("p.ATests", "p.A");
		seed.testExecution(true);

		ImpactGraph graph = graph(seed);

		assertThat(graph.testReferences(Set.of("p.A"))).hasSize(2);
		assertThat(graph.testReferences(Set.of("p.Other"))).isEmpty();
		assertThat(graph.conventionTests(Set.of("p.A"))).extracting(ImpactGraph.ConventionTest::testSymbol).containsExactly("p.ATests");
		assertThat(graph.stats().testReferencesAvailable()).isTrue();
		assertThat(graph.stats().testsAnalysed()).isTrue();
	}

	@Test
	void anOlderRunWithoutTestReferencesIsRecognised() {
		GraphSeeder seed = seeder();
		seed.testExecution(false);

		assertThat(graph(seed).stats().testReferencesAvailable()).isFalse();
		assertThat(graph(seed).stats().testsAnalysed()).isTrue();
	}

}
