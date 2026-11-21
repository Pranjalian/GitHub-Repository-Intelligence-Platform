package com.repointelligence.analysis.packs.java.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.packs.java.SpringArchitectureAnalyzer;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.ArchitectureResult;
import com.repointelligence.analysis.spi.ArchitectureResult.Endpoint;
import com.repointelligence.analysis.spi.ArchitectureResult.EntityRelation;
import com.repointelligence.analysis.spi.ArchitectureResult.Layer;
import com.repointelligence.analysis.spi.ArchitectureResult.Pattern;
import com.repointelligence.analysis.spi.ArchitectureResult.Symbol;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import com.repointelligence.architecture.domain.LayerMethod;
import com.repointelligence.architecture.domain.PatternType;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.Severity;
import com.repointelligence.support.InMemoryWorkspace;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpringDiscoveryTests {

	private final SpringArchitectureAnalyzer analyzer = new SpringArchitectureAnalyzer();

	// ── layers ───────────────────────────────────────────────────────────────────────────────────

	@Test
	void aRestControllerIsADetectedControllerAndAPackageNameAloneIsOnlyLikely() {
		AnalyzerResult result = analyze(new InMemoryWorkspace()
			.add("src/main/java/demo/api/ItemEndpoint.java", """
					package demo.api;
					import org.springframework.web.bind.annotation.RestController;
					@RestController
					public class ItemEndpoint {
					}
					""")
			.add("src/main/java/demo/service/PriceRules.java", """
					package demo.service;
					public class PriceRules {
						public int round(int value) {
							return value;
						}
					}
					""")
			.add("src/main/java/demo/service/Limits.java", """
					package demo.service;
					public final class Limits {
						public static final int MAX = 3;
					}
					"""));

		Layer controller = layer(result, "demo.api.ItemEndpoint").orElseThrow();
		assertThat(controller.layer()).isEqualTo(ArchitectureLayer.CONTROLLER);
		assertThat(controller.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(controller.method()).isEqualTo(LayerMethod.ANNOTATION);
		assertThat(controller.evidence()).singleElement().satisfies(e -> {
			assertThat(e.signal()).isEqualTo("@RestController");
			assertThat(e.line()).isEqualTo(3);
		});

		Layer heuristic = layer(result, "demo.service.PriceRules").orElseThrow();
		assertThat(heuristic.layer()).isEqualTo(ArchitectureLayer.SERVICE);
		assertThat(heuristic.confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(heuristic.method()).isEqualTo(LayerMethod.PACKAGE_HEURISTIC);

		assertThat(layer(result, "demo.service.Limits")).as("a constants holder has no layer").isEmpty();
	}

	@Test
	void plainComponentIsOnlyPossibleInfrastructureAndSpringDataInterfacesAreRepositories() {
		AnalyzerResult result = analyze(new InMemoryWorkspace()
			.add("src/main/java/demo/Clock.java", """
					package demo;
					import org.springframework.stereotype.Component;
					@Component
					public class Clock {
					}
					""")
			.add("src/main/java/demo/Things.java", """
					package demo;
					import org.springframework.data.repository.CrudRepository;
					public interface Things extends CrudRepository<Thing, Long> {
					}
					""")
			.add("src/main/java/demo/SpecialThings.java", """
					package demo;
					public interface SpecialThings extends Things {
					}
					""")
			.add("src/main/java/demo/Thing.java", """
					package demo;
					import jakarta.persistence.Entity;
					@Entity
					public class Thing {
					}
					"""));

		assertThat(layer(result, "demo.Clock").orElseThrow()).satisfies(l -> {
			assertThat(l.layer()).isEqualTo(ArchitectureLayer.INFRASTRUCTURE);
			assertThat(l.confidence()).isEqualTo(Confidence.POSSIBLE);
		});
		assertThat(layer(result, "demo.Things").orElseThrow()).satisfies(l -> {
			assertThat(l.layer()).isEqualTo(ArchitectureLayer.REPOSITORY);
			assertThat(l.confidence()).isEqualTo(Confidence.DETECTED);
			assertThat(l.method()).isEqualTo(LayerMethod.TYPE_HIERARCHY);
		});
		assertThat(layer(result, "demo.SpecialThings").orElseThrow().layer())
			.as("through a project interface").isEqualTo(ArchitectureLayer.REPOSITORY);
		assertThat(layer(result, "demo.Thing").orElseThrow().layer()).isEqualTo(ArchitectureLayer.ENTITY);
	}

	// ── endpoints ────────────────────────────────────────────────────────────────────────────────

	@Test
	void endpointPathsCombineClassAndMethodMappingsAndResolveConstants() {
		AnalyzerResult result = analyze(new InMemoryWorkspace().add("src/main/java/demo/ItemController.java", """
				package demo;
				import java.util.List;
				import org.springframework.http.ResponseEntity;
				import org.springframework.web.bind.annotation.*;
				@RestController
				@RequestMapping({"/api/", "/v2"})
				public class ItemController {
					static final String ITEMS = "/items";
					@GetMapping(ITEMS + "/{id}")
					public ResponseEntity<ItemDto> get(@PathVariable long id) {
						return null;
					}
					@PostMapping(path = ITEMS)
					public ResponseEntity<List<ItemDto>> create(@RequestBody NewItem item) {
						return null;
					}
					@RequestMapping(value = "/sync", method = RequestMethod.PUT)
					public void sync() {
					}
					@GetMapping(Routes.EXTERNAL)
					public String external() {
						return "";
					}
				}
				""").add("src/main/java/demo/ItemDto.java", "package demo;\npublic record ItemDto(long id) {}\n")
			.add("src/main/java/demo/NewItem.java", "package demo;\npublic record NewItem(String name) {}\n"));
		List<Endpoint> endpoints = result.architecture().endpoints();

		assertThat(endpoints).extracting(e -> e.httpMethod() + " " + e.route())
			.containsExactlyInAnyOrder("GET /api/items/{id}", "GET /v2/items/{id}", "POST /api/items", "POST /v2/items",
					"PUT /api/sync", "PUT /v2/sync", "GET /api/{Routes.EXTERNAL}", "GET /v2/{Routes.EXTERNAL}");
		Endpoint get = endpoints.stream().filter(e -> e.route().equals("/api/items/{id}")).findFirst().orElseThrow();
		assertThat(get.routeResolved()).isTrue();
		assertThat(get.responseType()).as("ResponseEntity is unwrapped").isEqualTo("demo.ItemDto");
		assertThat(get.requestType()).isNull();
		assertThat(get.line()).isEqualTo(9);
		Endpoint create = endpoints.stream().filter(e -> e.route().equals("/api/items")).findFirst().orElseThrow();
		assertThat(create.requestType()).isEqualTo("demo.NewItem");
		assertThat(create.responseType()).isEqualTo("demo.ItemDto");
		assertThat(endpoints).filteredOn(e -> e.route().contains("Routes.EXTERNAL")).allMatch(e -> !e.routeResolved());
		assertThat(endpoints).filteredOn(e -> e.route().endsWith("/sync")).allMatch(e -> e.responseType() == null);

		assertThat(layer(result, "demo.ItemDto").orElseThrow()).satisfies(l -> {
			assertThat(l.layer()).isEqualTo(ArchitectureLayer.DTO);
			assertThat(l.method()).isEqualTo(LayerMethod.API_SIGNATURE);
			assertThat(l.confidence()).isEqualTo(Confidence.LIKELY);
		});
		assertThat(layer(result, "demo.NewItem").orElseThrow().layer()).isEqualTo(ArchitectureLayer.DTO);
		assertThat(result.limitations()).anyMatch(l -> l.contains("endpoint paths"));
	}

	@Test
	void anMvcControllerReturningViewNamesHasNoResponseTypeAndAMethodlessRequestMappingIsAny() {
		AnalyzerResult result = analyze(new InMemoryWorkspace().add("src/main/java/demo/PageController.java", """
				package demo;
				import org.springframework.stereotype.Controller;
				import org.springframework.web.bind.annotation.RequestMapping;
				@Controller
				class PageController {
					@RequestMapping("/home")
					String home() {
						return "home";
					}
				}
				"""));

		assertThat(result.architecture().endpoints()).singleElement().satisfies(e -> {
			assertThat(e.httpMethod()).isEqualTo("ANY");
			assertThat(e.route()).isEqualTo("/home");
			assertThat(e.responseType()).isNull();
		});
	}

	@Test
	void joinNormalisesSlashes() {
		assertThat(SpringDiscovery.join("", "")).isEqualTo("/");
		assertThat(SpringDiscovery.join("/api/", "/x/")).isEqualTo("/api/x");
		assertThat(SpringDiscovery.join("api", "")).isEqualTo("/api");
	}

	// ── entities ─────────────────────────────────────────────────────────────────────────────────

	@Test
	void jpaRelationshipsAndRepositoryEntityMappingsAreRead() {
		ArchitectureResult architecture = analyze(InMemoryWorkspace.fromFixture("spring-boot-architecture")).architecture();
		List<EntityRelation> relations = architecture.entityRelations();

		EntityRelation lines = relation(relations, "lines");
		assertThat(lines.type()).isEqualTo(EntityRelationshipType.ONE_TO_MANY);
		assertThat(lines.targetName()).isEqualTo("com.example.shop.domain.OrderLine");
		assertThat(lines.targetKey()).isNotNull();
		assertThat(lines.attributes()).containsEntry("mappedBy", "order").containsEntry("cascade", "CascadeType.ALL");

		EntityRelation customer = relation(relations, "customer");
		assertThat(customer.type()).isEqualTo(EntityRelationshipType.MANY_TO_ONE);
		assertThat(customer.attributes()).containsEntry("fetch", "FetchType.LAZY");
		assertThat(relation(relations, "order").attributes()).containsEntry("joinColumn", "order_id");
		assertThat(relation(relations, "orders").targetName()).isEqualTo("com.example.shop.domain.Order");

		List<EntityRelation> repositories = relations.stream()
			.filter(r -> r.type() == EntityRelationshipType.REPOSITORY_FOR)
			.toList();
		assertThat(repositories).extracting(EntityRelation::targetName)
			.containsExactlyInAnyOrder("com.example.shop.domain.Order", "com.example.shop.domain.Customer");
		assertThat(repositories).allSatisfy(r -> assertThat(r.attributes()).containsEntry("idType", "Long"));
	}

	// ── cycles and patterns ──────────────────────────────────────────────────────────────────────

	@Test
	void anInjectionCycleIsReportedWithEvidence() {
		AnalyzerResult result = analyze(InMemoryWorkspace.fromFixture("spring-boot-architecture"));

		List<FindingResult> cycles = result.findings()
			.stream()
			.filter(f -> f.ruleId().equals("injection-cycle"))
			.toList();
		assertThat(cycles).singleElement().satisfies(cycle -> {
			assertThat(cycle.category()).isEqualTo(AnalysisCategory.ARCHITECTURE);
			assertThat(cycle.severity()).isEqualTo(Severity.MEDIUM);
			assertThat(cycle.confidence()).isEqualTo(Confidence.DETECTED);
			assertThat(cycle.evidence().get("members")).asList()
				.containsExactlyInAnyOrder("com.example.shop.service.AuditService", "com.example.shop.service.ReportService");
			assertThat(cycle.evidence().get("edges")).asList().hasSize(2);
		});
		// the bidirectional JPA associations (Order <-> Customer, Order <-> OrderLine) are not reported
		assertThat(result.findings()).noneMatch(f -> f.evidence().toString().contains("domain.OrderLine"));
	}

	@Test
	void patternCandidatesComeFromStructureNotNames() {
		ArchitectureResult architecture = analyze(InMemoryWorkspace.fromFixture("spring-boot-architecture")).architecture();
		List<Pattern> patterns = architecture.patterns();

		Pattern strategy = pattern(patterns, PatternType.STRATEGY);
		assertThat(strategy.confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(strategy.evidence().get("implementations")).asList()
			.containsExactlyInAnyOrder("com.example.shop.service.SeasonalDiscount", "com.example.shop.service.LoyaltyDiscount");

		Pattern factory = pattern(patterns, PatternType.FACTORY);
		assertThat(factory.confidence()).isEqualTo(Confidence.POSSIBLE);
		assertThat(factory.evidence()).containsEntry("method", "com.example.shop.service.NotifierProvider#create");

		Pattern adapter = pattern(patterns, PatternType.ADAPTER);
		assertThat(adapter.confidence()).isEqualTo(Confidence.POSSIBLE);
		assertThat(adapter.evidence()).containsEntry("adaptee", "com.example.shop.service.LegacyPaymentClient");

		assertThat(patterns).filteredOn(p -> p.pattern() == PatternType.REPOSITORY)
			.hasSize(2)
			.allMatch(p -> p.confidence() == Confidence.DETECTED);

		// PaymentFactory is named like a factory but creates nothing; OrderService delegates but is a service
		String paymentFactory = key(architecture, "com.example.shop.service.PaymentFactory");
		String orderService = key(architecture, "com.example.shop.service.OrderService");
		assertThat(patterns).noneMatch(p -> p.symbolKeys().contains(paymentFactory));
		assertThat(patterns).filteredOn(p -> p.pattern() == PatternType.ADAPTER)
			.noneMatch(p -> p.symbolKeys().contains(orderService));
	}

	@Test
	void aClassNamedFactoryWithoutFactoryBehaviourIsNotFlagged() {
		ArchitectureResult architecture = analyze(new InMemoryWorkspace()
			.add("src/main/java/demo/PaymentFactory.java", """
					package demo;
					public class PaymentFactory {
						public Payment create() {
							return new CardPayment();
						}
					}
					""")
			.add("src/main/java/demo/Payment.java", "package demo;\npublic interface Payment {}\n")
			.add("src/main/java/demo/CardPayment.java", "package demo;\npublic class CardPayment implements Payment {}\n"))
			.architecture();

		assertThat(architecture.patterns()).noneMatch(p -> p.pattern() == PatternType.FACTORY);
	}

	@Test
	void tarjanFindsEachStronglyConnectedComponentOnce() {
		java.util.Map<String, java.util.Map<String, ArchitectureResult.Edge>> graph = new java.util.LinkedHashMap<>();
		ArchitectureResult.Edge edge = new ArchitectureResult.Edge("a", "b", "b",
				com.repointelligence.architecture.domain.RelationshipType.INJECTS,
				com.repointelligence.architecture.domain.EdgeResolution.RESOLVED, java.util.Map.of());
		graph.put("a", java.util.Map.of("b", edge));
		graph.put("b", java.util.Map.of("c", edge));
		graph.put("c", java.util.Map.of("a", edge, "d", edge));
		graph.put("d", java.util.Map.of());

		List<List<String>> components = SpringDiscovery.Tarjan.components(graph);

		assertThat(components).hasSize(2);
		assertThat(components).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrder("a", "b", "c"));
		assertThat(components).anySatisfy(c -> assertThat(c).containsExactly("d"));
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private AnalyzerResult analyze(InMemoryWorkspace workspace) {
		return analyzer.analyze(workspace.context());
	}

	private static Optional<Layer> layer(AnalyzerResult result, String fqn) {
		String key = key(result.architecture(), fqn);
		return result.architecture().layers().stream().filter(l -> l.symbolKey().equals(key)).findFirst();
	}

	private static String key(ArchitectureResult architecture, String fqn) {
		return architecture.symbols()
			.stream()
			.filter(s -> s.fullyQualifiedName().equals(fqn))
			.map(Symbol::key)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no symbol " + fqn));
	}

	private static EntityRelation relation(List<EntityRelation> relations, String field) {
		return relations.stream()
			.filter(r -> field.equals(r.fieldName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no relation on field " + field));
	}

	private static Pattern pattern(List<Pattern> patterns, PatternType type) {
		List<Pattern> matches = patterns.stream().filter(p -> p.pattern() == type).toList();
		assertThat(matches).as(type + " candidates").hasSize(1);
		return matches.get(0);
	}

}
