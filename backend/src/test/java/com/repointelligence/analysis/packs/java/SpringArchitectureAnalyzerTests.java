package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.ArchitectureResult;
import com.repointelligence.analysis.spi.ArchitectureResult.Edge;
import com.repointelligence.analysis.spi.ArchitectureResult.Symbol;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.support.InMemoryWorkspace;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The multi-class Spring Boot fixture through the analyzer: the expected symbol and edge graph. */
class SpringArchitectureAnalyzerTests {

	private static final String SHOP = "com.example.shop.";

	private final SpringArchitectureAnalyzer analyzer = new SpringArchitectureAnalyzer();

	@Test
	void descriptorIsADeepJavaArchitectureAnalyzer() {
		assertThat(analyzer.descriptor().category()).isEqualTo(AnalysisCategory.ARCHITECTURE);
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.DEEP);
		assertThat(analyzer.descriptor().supportedLanguages()).containsExactly("Java");
	}

	@Test
	void appliesOnlyWithProductionJavaSources() {
		assertThat(analyzer.applicability(InMemoryWorkspace.fromFixture("react-typescript").context()))
			.isEqualTo(Applicability.notApplicable());
		assertThat(analyzer.applicability(new InMemoryWorkspace()
			.add("src/test/java/demo/OnlyTests.java", "package demo;\nclass OnlyTests {}\n")
			.context())).isEqualTo(Applicability.notApplicable());
		assertThat(analyzer.applicability(InMemoryWorkspace.fromFixture("spring-boot-architecture").context()).isApplicable())
			.isTrue();
	}

	@Test
	void theSpringBootFixtureProducesTheExpectedSymbolAndEdgeGraph() {
		AnalyzerResult result = analyzer.analyze(InMemoryWorkspace.fromFixture("spring-boot-architecture").context());
		ArchitectureResult architecture = result.architecture();

		Map<String, Symbol> types = architecture.symbols()
			.stream()
			.filter(s -> s.type().isType())
			.collect(Collectors.toMap(Symbol::fullyQualifiedName, s -> s));
		assertThat(types.keySet()).hasSize(27)
			.contains(SHOP + "web.OrderController", SHOP + "service.OrderService", SHOP + "repository.OrderRepository",
					SHOP + "domain.Order", SHOP + "web.CreateOrderRequest")
			.doesNotContain(SHOP + "OrderServiceTests");
		assertThat(architecture.symbols()).allMatch(s -> ".".equals(s.modulePath()));

		// injection: controller -> service -> repositories and the discount strategies
		assertThat(injected(architecture, types, "web.OrderController")).containsExactly(SHOP + "service.OrderService");
		assertThat(injected(architecture, types, "service.OrderService")).containsExactlyInAnyOrder(
				SHOP + "repository.OrderRepository", SHOP + "repository.CustomerRepository",
				SHOP + "service.DiscountPolicy");
		assertThat(injected(architecture, types, "service.AuditService")).containsExactly(SHOP + "service.ReportService");
		assertThat(injected(architecture, types, "service.ReportService")).containsExactly(SHOP + "service.AuditService");

		// calls: resolved into the project, unresolved into Spring Data's inherited methods
		String list = key(architecture, SHOP + "web.OrderController#list");
		Edge toService = edges(architecture, list, RelationshipType.CALLS).get(0);
		assertThat(toService.resolution()).isEqualTo(EdgeResolution.RESOLVED);
		assertThat(toService.targetKey()).isEqualTo(key(architecture, SHOP + "service.OrderService#findAll"));
		String findAll = key(architecture, SHOP + "service.OrderService#findAll");
		assertThat(edges(architecture, findAll, RelationshipType.CALLS)).filteredOn(e -> "findAll".equals(e.evidence().get("method")))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.resolution()).isEqualTo(EdgeResolution.UNRESOLVED);
				assertThat(e.targetName()).isEqualTo(SHOP + "repository.OrderRepository#findAll");
			});

		// inheritance
		assertThat(edges(architecture, types.get(SHOP + "service.SeasonalDiscount").key(), RelationshipType.IMPLEMENTS))
			.singleElement()
			.satisfies(e -> assertThat(e.targetKey()).isEqualTo(types.get(SHOP + "service.DiscountPolicy").key()));
		assertThat(edges(architecture, types.get(SHOP + "repository.OrderRepository").key(), RelationshipType.EXTENDS))
			.extracting(Edge::targetName)
			.containsExactly("org.springframework.data.jpa.repository.JpaRepository");

		// every edge is anchored to a file and line
		assertThat(architecture.edges()).allSatisfy(e -> assertThat(e.evidence()).containsKeys("file", "line"));
		assertThat(result.configSummary()).containsEntry("types", 27).containsEntry("javaFiles", 27);
		assertThat(result.limitations()).anyMatch(l -> l.contains("UNRESOLVED"));
	}

	@Test
	void mavenModuleRelationshipsAreReturned() {
		InMemoryWorkspace workspace = InMemoryWorkspace.fromFixture("maven-multi-module")
			.add("web/src/main/java/com/acme/web/App.java", "package com.acme.web;\npublic class App {}\n");

		ArchitectureResult architecture = analyzer.analyze(workspace.context()).architecture();

		assertThat(architecture.moduleDependencies()).singleElement()
			.satisfies(d -> assertThat(d.sourceModulePath() + "->" + d.targetModulePath()).isEqualTo("web->core"));
		assertThat(architecture.symbols()).filteredOn(s -> s.fullyQualifiedName().equals("com.acme.web.App"))
			.singleElement()
			.satisfies(s -> assertThat(s.modulePath()).isEqualTo("web"));
	}

	private static List<String> injected(ArchitectureResult architecture, Map<String, Symbol> types, String type) {
		return edges(architecture, types.get(SHOP + type).key(), RelationshipType.INJECTS).stream()
			.map(Edge::targetName)
			.toList();
	}

	private static List<Edge> edges(ArchitectureResult architecture, String sourceKey, RelationshipType type) {
		return architecture.edges().stream().filter(e -> e.sourceKey().equals(sourceKey) && e.type() == type).toList();
	}

	private static String key(ArchitectureResult architecture, String fqn) {
		return architecture.symbols()
			.stream()
			.filter(s -> s.fullyQualifiedName().equals(fqn))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no symbol " + fqn))
			.key();
	}

}
