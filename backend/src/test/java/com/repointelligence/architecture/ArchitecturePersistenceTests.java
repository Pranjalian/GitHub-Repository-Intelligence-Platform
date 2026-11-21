package com.repointelligence.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.ApiEndpoint;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.CodeSymbol;
import com.repointelligence.architecture.domain.DependencyEdge;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.EntityRelationship;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import com.repointelligence.architecture.domain.LayerAssignment;
import com.repointelligence.architecture.domain.LayerMethod;
import com.repointelligence.architecture.domain.ModuleDependency;
import com.repointelligence.architecture.domain.PatternCandidate;
import com.repointelligence.architecture.domain.PatternType;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.architecture.infrastructure.persistence.ApiEndpointRepository;
import com.repointelligence.architecture.infrastructure.persistence.CodeSymbolRepository;
import com.repointelligence.architecture.infrastructure.persistence.DependencyEdgeRepository;
import com.repointelligence.architecture.infrastructure.persistence.EntityRelationshipRepository;
import com.repointelligence.architecture.infrastructure.persistence.LayerAssignmentRepository;
import com.repointelligence.architecture.infrastructure.persistence.ModuleDependencyRepository;
import com.repointelligence.architecture.infrastructure.persistence.PatternCandidateRepository;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.support.EmbeddedPostgresConfig;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The MVP 2 architecture tables on real PostgreSQL: schema, entity round trips (Hibernate validates the
 * mappings against the Flyway schema when the context starts), constraints, cascade on run deletion and
 * bulk insert of a medium-sized symbol graph.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class ArchitecturePersistenceTests {

	private static final AtomicInteger SHA_COUNTER = new AtomicInteger();

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private CodeSymbolRepository symbols;

	@Autowired
	private DependencyEdgeRepository edges;

	@Autowired
	private ApiEndpointRepository endpoints;

	@Autowired
	private EntityRelationshipRepository entityRelationships;

	@Autowired
	private LayerAssignmentRepository layers;

	@Autowired
	private ModuleDependencyRepository moduleDependencies;

	@Autowired
	private PatternCandidateRepository patterns;

	private record Scope(UUID snapshotId, UUID runId) {
	}

	/** Data is committed (the bulk test needs real flushes), so it is removed again: the database is shared. */
	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	@Test
	void architectureTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

		assertThat(tables).contains("code_symbol", "dependency_edge", "api_endpoint", "entity_relationship",
				"module_dependency", "layer_assignment", "pattern_candidate");
	}

	@Test
	void everyArchitectureRowRoundTrips() {
		Scope scope = newRun();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(status -> {
			RepositorySnapshot snapshot = em.getReference(RepositorySnapshot.class, scope.snapshotId());
			AnalysisRun run = em.getReference(AnalysisRun.class, scope.runId());
			CodeSymbol controller = new CodeSymbol(snapshot, run, SymbolType.CLASS, "com.example.OwnerController",
					"src/main/java/com/example/OwnerController.java");
			controller.setAnnotations(List.of(new AnnotationInfo("RestController", Map.of()),
					new AnnotationInfo("RequestMapping", Map.of("value", "/owners"))));
			controller.setModifiers(List.of("public"));
			controller.setStartLine(10);
			controller.setEndLine(40);
			controller.setModulePath(".");
			em.persist(controller);
			CodeSymbol handler = new CodeSymbol(snapshot, run, SymbolType.METHOD, "com.example.OwnerController#list",
					"src/main/java/com/example/OwnerController.java");
			handler.setParent(controller);
			handler.setSignature("list()");
			em.persist(handler);
			CodeSymbol entity = new CodeSymbol(snapshot, run, SymbolType.CLASS, "com.example.Owner",
					"src/main/java/com/example/Owner.java");
			em.persist(entity);

			DependencyEdge resolved = new DependencyEdge(snapshot, run, controller, RelationshipType.INJECTS,
					EdgeResolution.RESOLVED);
			resolved.setTarget(entity);
			resolved.setEvidence(Map.of("file", "src/main/java/com/example/OwnerController.java", "line", 12));
			em.persist(resolved);
			DependencyEdge unresolved = new DependencyEdge(snapshot, run, controller, RelationshipType.IMPORTS,
					EdgeResolution.UNRESOLVED);
			unresolved.setTargetUnresolvedName("org.springframework.web.bind.annotation.RestController");
			em.persist(unresolved);

			ApiEndpoint endpoint = new ApiEndpoint(snapshot, run, "GET", "/owners", controller, handler,
					"src/main/java/com/example/OwnerController.java");
			endpoint.setResponseType("java.util.List<com.example.Owner>");
			endpoint.setLineNumber(20);
			em.persist(endpoint);

			EntityRelationship relationship = new EntityRelationship(snapshot, run, entity,
					EntityRelationshipType.ONE_TO_MANY);
			relationship.setTargetUnresolvedName("Pet");
			relationship.setFieldName("pets");
			relationship.setAttributes(Map.of("mappedBy", "owner"));
			em.persist(relationship);

			ModuleDependency module = new ModuleDependency(snapshot, run, "web", "core");
			module.setEvidence(List.of(new Evidence("web/pom.xml", 30, "depends on com.example:core")));
			em.persist(module);

			LayerAssignment layer = new LayerAssignment(snapshot, run, controller, ArchitectureLayer.CONTROLLER,
					Confidence.DETECTED, LayerMethod.ANNOTATION);
			layer.setEvidence(List.of(new Evidence("src/main/java/com/example/OwnerController.java", 9, "@RestController")));
			em.persist(layer);

			em.persist(new PatternCandidate(snapshot, run, PatternType.REPOSITORY, List.of(entity.getId()),
					Confidence.DETECTED));
		});
		em.clear();

		tx.executeWithoutResult(status -> {
			CodeSymbol controller = symbols.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId())
				.stream()
				.filter(s -> s.getFullyQualifiedName().equals("com.example.OwnerController"))
				.findFirst()
				.orElseThrow();
			assertThat(controller.getAnnotations()).extracting(AnnotationInfo::name)
				.containsExactly("RestController", "RequestMapping");
			assertThat(controller.getAnnotations().get(1).attributes()).containsEntry("value", "/owners");
			assertThat(controller.getModifiers()).containsExactly("public");

			List<DependencyEdge> runEdges = edges.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId());
			assertThat(runEdges).hasSize(2);
			assertThat(runEdges).filteredOn(e -> e.getResolution() == EdgeResolution.UNRESOLVED)
				.singleElement()
				.satisfies(e -> {
					assertThat(e.getTarget()).isNull();
					assertThat(e.getTargetUnresolvedName()).endsWith("RestController");
				});
			assertThat(runEdges).filteredOn(e -> e.getResolution() == EdgeResolution.RESOLVED)
				.singleElement()
				.satisfies(e -> assertThat(e.getEvidence()).containsEntry("line", 12));

			ApiEndpoint endpoint = endpoints.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()).get(0);
			assertThat(endpoint.isRouteResolved()).isTrue();
			assertThat(endpoint.getHandler().getParent().getId()).isEqualTo(controller.getId());

			assertThat(entityRelationships.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()).get(0)
				.getAttributes()).containsEntry("mappedBy", "owner");
			assertThat(moduleDependencies.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()).get(0)
				.getEvidence()).extracting(Evidence::line).containsExactly(30);
			assertThat(layers.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()).get(0).getMethod())
				.isEqualTo(LayerMethod.ANNOTATION);
			PatternCandidate pattern = patterns.findBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()).get(0);
			assertThat(pattern.getSymbolIds()).hasSize(1).first().isInstanceOf(UUID.class);
		});
	}

	@Test
	void anEdgeNeedsATargetOrAName() {
		Scope scope = newRun();
		UUID source = insertSymbol(scope, "com.example.A");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO dependency_edge (snapshot_id, run_id, source_symbol_id, relationship_type, resolution) "
						+ "VALUES (?, ?, ?, 'CALLS', 'UNRESOLVED')",
				scope.snapshotId(), scope.runId(), source))
			.hasMessageContaining("ck_dependency_edge_target");
	}

	@Test
	void deletingARunRemovesItsArchitectureData() {
		Scope scope = newRun();
		UUID a = insertSymbol(scope, "com.example.A");
		UUID b = insertSymbol(scope, "com.example.B");
		jdbc.update("INSERT INTO dependency_edge (snapshot_id, run_id, source_symbol_id, target_symbol_id, "
				+ "relationship_type, resolution) VALUES (?, ?, ?, ?, 'INJECTS', 'RESOLVED')", scope.snapshotId(),
				scope.runId(), a, b);
		jdbc.update("INSERT INTO layer_assignment (snapshot_id, run_id, symbol_id, layer, confidence, method) "
				+ "VALUES (?, ?, ?, 'SERVICE', 'DETECTED', 'ANNOTATION')", scope.snapshotId(), scope.runId(), a);

		jdbc.update("DELETE FROM analysis_run WHERE id = ?", scope.runId());

		for (String table : List.of("code_symbol", "dependency_edge", "layer_assignment")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE run_id = ?", Long.class,
					scope.runId()))
				.as(table)
				.isZero();
		}
	}

	/**
	 * A medium project (about 300 types with 3,000 members and 12,000 edges) is stored through JPA with
	 * batching in well under the limit; the generous bound only guards against pathological regressions
	 * such as one round trip per row without batching.
	 */
	@Test
	void bulkInsertOfAMediumSymbolGraphPerformsAcceptably() {
		Scope scope = newRun();
		int types = 300;
		int membersPerType = 10;
		int edgesPerMember = 4;
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		long started = System.nanoTime();
		tx.executeWithoutResult(status -> {
			RepositorySnapshot snapshot = em.getReference(RepositorySnapshot.class, scope.snapshotId());
			AnalysisRun run = em.getReference(AnalysisRun.class, scope.runId());
			List<UUID> typeIds = new ArrayList<>();
			for (int t = 0; t < types; t++) {
				CodeSymbol type = new CodeSymbol(snapshot, run, SymbolType.CLASS, "com.example.pkg.Type" + t,
						"src/main/java/com/example/pkg/Type" + t + ".java");
				type.setAnnotations(List.of(new AnnotationInfo("Service", Map.of())));
				em.persist(type);
				typeIds.add(type.getId());
			}
			em.flush();
			em.clear();
			int pending = 0;
			for (int t = 0; t < types; t++) {
				CodeSymbol type = em.getReference(CodeSymbol.class, typeIds.get(t));
				for (int m = 0; m < membersPerType; m++) {
					CodeSymbol member = new CodeSymbol(snapshot, run, SymbolType.METHOD,
							"com.example.pkg.Type" + t + "#method" + m, "src/main/java/com/example/pkg/Type" + t + ".java");
					member.setParent(type);
					member.setSignature("method" + m + "(java.lang.String)");
					em.persist(member);
					for (int e = 0; e < edgesPerMember; e++) {
						DependencyEdge edge = new DependencyEdge(snapshot, run, member, RelationshipType.CALLS,
								EdgeResolution.RESOLVED);
						edge.setTarget(em.getReference(CodeSymbol.class, typeIds.get((t + e + 1) % types)));
						edge.setEvidence(Map.of("file", "src/main/java/com/example/pkg/Type" + t + ".java", "line", m));
						em.persist(edge);
					}
					if (++pending % 500 == 0) {
						em.flush();
						em.clear();
						type = em.getReference(CodeSymbol.class, typeIds.get(t));
					}
				}
			}
			em.flush();
			em.clear();
		});
		Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

		assertThat(symbols.countBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()))
			.isEqualTo(types + (long) types * membersPerType);
		assertThat(edges.countBySnapshotIdAndRunId(scope.snapshotId(), scope.runId()))
			.isEqualTo((long) types * membersPerType * edgesPerMember);
		assertThat(elapsed).as("bulk insert time").isLessThan(Duration.ofSeconds(60));
	}

	private Scope newRun() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		return tx.execute(status -> {
			String suffix = Integer.toString(SHA_COUNTER.incrementAndGet());
			TrackedRepository repository = new TrackedRepository("arch-owner", "arch-repo-" + UUID.randomUUID(),
					"https://github.com/arch-owner/arch-repo-" + suffix);
			em.persist(repository);
			String sha = String.format("%40s", Integer.toHexString(SHA_COUNTER.get())).replace(' ', 'c');
			RepositorySnapshot snapshot = new RepositorySnapshot(repository, sha);
			em.persist(snapshot);
			AnalysisRun run = new AnalysisRun(snapshot, RunStatus.ANALYZING);
			em.persist(run);
			return new Scope(snapshot.getId(), run.getId());
		});
	}

	private UUID insertSymbol(Scope scope, String fqn) {
		return jdbc.queryForObject("INSERT INTO code_symbol (snapshot_id, run_id, symbol_type, fully_qualified_name, "
				+ "file_path) VALUES (?, ?, 'CLASS', ?, ?) RETURNING id", UUID.class, scope.snapshotId(), scope.runId(),
				fqn, fqn.replace('.', '/') + ".java");
	}

}
