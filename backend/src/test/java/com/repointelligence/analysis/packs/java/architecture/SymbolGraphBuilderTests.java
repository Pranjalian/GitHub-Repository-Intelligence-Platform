package com.repointelligence.analysis.packs.java.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.packs.java.architecture.SymbolGraphBuilder.Graph;
import com.repointelligence.analysis.packs.java.maven.MavenPomParser;
import com.repointelligence.analysis.spi.ArchitectureResult.Edge;
import com.repointelligence.analysis.spi.ArchitectureResult.ModuleDependency;
import com.repointelligence.analysis.spi.ArchitectureResult.Symbol;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.RelationshipType;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.support.InMemoryWorkspace;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SymbolGraphBuilderTests {

	private static final String SERVICE = """
			package demo.service;

			import demo.repo.OrderRepository;
			import java.util.List;
			import org.springframework.beans.factory.annotation.Autowired;
			import org.springframework.stereotype.Service;
			import org.springframework.web.bind.annotation.RequestMapping;
			import org.springframework.web.client.RestTemplate;

			@Service
			@RequestMapping(value = "/orders", produces = "application/json")
			public class OrderService {

				@Autowired
				private AuditLog audit;

				private final OrderRepository repository;

				private final RestTemplate rest;

				private final List<String> names = List.of();

				public OrderService(OrderRepository repository, RestTemplate rest) {
					this.repository = repository;
					this.rest = rest;
				}

				public String fetch(long id) {
					audit.log("fetch");
					names.size();
					repository.findOne(id);
					helper();
					return rest.getForObject("http://example.org", String.class);
				}

				private void helper() {
					Util.trim("x");
				}

				public static class Nested {
				}

			}
			""";

	private static final String AUDIT = """
			package demo.service;

			public class AuditLog {

				public void log(String event) {
				}

				public void log(String event, int level) {
				}

			}
			""";

	private static final String UTIL = """
			package demo.service;

			public final class Util {

				public static String trim(String value) {
					return value.trim();
				}

			}
			""";

	private static final String REPO = """
			package demo.repo;

			import org.springframework.data.jpa.repository.JpaRepository;

			public interface OrderRepository extends JpaRepository<Order, Long> {

				Order findOne(long id);

			}
			""";

	private static final String ORDER = """
			package demo.repo;

			public record Order(long id, String name) {
			}
			""";

	@Test
	void typesAndMembersBecomeSymbolsWithAnnotationsAndModifiers() {
		Graph graph = build(new InMemoryWorkspace().add("src/main/java/demo/service/OrderService.java", SERVICE)
			.add("src/main/java/demo/repo/Order.java", ORDER));

		Symbol service = symbol(graph, "demo.service.OrderService");
		assertThat(service.type()).isEqualTo(SymbolType.CLASS);
		assertThat(service.parentKey()).isNull();
		assertThat(service.modifiers()).containsExactly("public");
		assertThat(service.annotations()).extracting(AnnotationInfo::name).containsExactly("Service", "RequestMapping");
		assertThat(service.annotations().get(1).attributes()).containsEntry("value", "/orders")
			.containsEntry("produces", "application/json");
		assertThat(service.startLine()).isEqualTo(10);

		Symbol nested = symbol(graph, "demo.service.OrderService.Nested");
		assertThat(nested.parentKey()).isEqualTo(service.key());

		Symbol fetch = symbol(graph, "demo.service.OrderService#fetch");
		assertThat(fetch.type()).isEqualTo(SymbolType.METHOD);
		assertThat(fetch.signature()).isEqualTo("fetch(long)");
		assertThat(fetch.parentKey()).isEqualTo(service.key());

		Symbol constructor = symbol(graph, "demo.service.OrderService#<init>");
		assertThat(constructor.type()).isEqualTo(SymbolType.CONSTRUCTOR);
		assertThat(constructor.signature()).isEqualTo("OrderService(OrderRepository, RestTemplate)");

		Symbol field = symbol(graph, "demo.service.OrderService#audit");
		assertThat(field.type()).isEqualTo(SymbolType.FIELD);
		assertThat(field.signature()).isEqualTo("AuditLog");
		assertThat(field.annotations()).extracting(AnnotationInfo::name).containsExactly("Autowired");

		Symbol record = symbol(graph, "demo.repo.Order");
		assertThat(record.type()).isEqualTo(SymbolType.RECORD);
		assertThat(symbol(graph, "demo.repo.Order#name").type()).isEqualTo(SymbolType.FIELD);
	}

	@Test
	void fieldAndConstructorInjectionBecomeInjectsEdges() {
		Graph graph = build(projectFiles());
		String service = symbol(graph, "demo.service.OrderService").key();

		List<Edge> injects = edges(graph, service, RelationshipType.INJECTS);

		assertThat(injects).extracting(Edge::targetName)
			.containsExactlyInAnyOrder("demo.service.AuditLog", "demo.repo.OrderRepository",
					"org.springframework.web.client.RestTemplate");
		Edge field = injects.stream().filter(e -> e.targetName().equals("demo.service.AuditLog")).findFirst().orElseThrow();
		assertThat(field.resolution()).isEqualTo(EdgeResolution.RESOLVED);
		assertThat(field.targetKey()).isEqualTo(symbol(graph, "demo.service.AuditLog").key());
		assertThat(field.evidence()).containsEntry("via", "field")
			.containsEntry("confidence", "DETECTED")
			.containsEntry("file", "src/main/java/demo/service/OrderService.java")
			.containsEntry("line", 15);
		Edge constructor = injects.stream()
			.filter(e -> e.targetName().equals("demo.repo.OrderRepository"))
			.findFirst()
			.orElseThrow();
		assertThat(constructor.evidence()).containsEntry("via", "constructor")
			.containsEntry("signal", "single constructor of a Spring bean, parameter repository");
		Edge thirdParty = injects.stream()
			.filter(e -> e.targetName().equals("org.springframework.web.client.RestTemplate"))
			.findFirst()
			.orElseThrow();
		assertThat(thirdParty.resolution()).isEqualTo(EdgeResolution.UNRESOLVED);
		assertThat(thirdParty.targetKey()).isNull();
	}

	@Test
	void aSingleConstructorOfAPlainClassIsNotInjection() {
		Graph graph = build(new InMemoryWorkspace().add("src/main/java/demo/Plain.java", """
				package demo;
				public class Plain {
					private final Helper helper;
					public Plain(Helper helper) {
						this.helper = helper;
					}
				}
				""").add("src/main/java/demo/Helper.java", "package demo;\npublic class Helper {}\n"));

		assertThat(edges(graph, symbol(graph, "demo.Plain").key(), RelationshipType.INJECTS)).isEmpty();
	}

	@Test
	void lombokConstructorInjectionIsLikelyAndCollectionsInjectTheirElementType() {
		Graph graph = build(new InMemoryWorkspace().add("src/main/java/demo/Checkout.java", """
				package demo;
				import java.util.List;
				import java.util.Map;
				import lombok.RequiredArgsConstructor;
				import org.springframework.stereotype.Component;
				@Component
				@RequiredArgsConstructor
				public class Checkout {
					private static final int LIMIT = 3;
					private final List<Policy> policies;
					private final Map<String, Policy> byName;
					private final String label = "x";
					private int counter;
				}
				""").add("src/main/java/demo/Policy.java", "package demo;\npublic interface Policy {}\n"));

		List<Edge> injects = edges(graph, symbol(graph, "demo.Checkout").key(), RelationshipType.INJECTS);

		// List<Policy> and Map<String, Policy> both inject Policy beans: one merged edge
		assertThat(injects).singleElement().satisfies(edge -> {
			assertThat(edge.targetName()).isEqualTo("demo.Policy");
			assertThat(edge.resolution()).isEqualTo(EdgeResolution.RESOLVED);
			assertThat(edge.evidence()).containsEntry("confidence", "LIKELY")
				.containsEntry("container", "java.util.List")
				.containsEntry("occurrences", 2);
		});
	}

	@Test
	void callsOnProjectTypesResolveAndThirdPartyCallsStayUnresolvedWithTheirName() {
		Graph graph = build(projectFiles());
		String fetch = symbol(graph, "demo.service.OrderService#fetch").key();

		List<Edge> calls = edges(graph, fetch, RelationshipType.CALLS);

		Edge thirdParty = call(calls, "getForObject");
		assertThat(thirdParty.resolution()).isEqualTo(EdgeResolution.UNRESOLVED);
		assertThat(thirdParty.targetKey()).isNull();
		assertThat(thirdParty.targetName()).isEqualTo("org.springframework.web.client.RestTemplate#getForObject");

		Edge repository = call(calls, "findOne");
		assertThat(repository.resolution()).isEqualTo(EdgeResolution.RESOLVED);
		assertThat(repository.targetKey()).isEqualTo(symbol(graph, "demo.repo.OrderRepository#findOne").key());

		Edge implicit = call(calls, "helper");
		assertThat(implicit.targetKey()).isEqualTo(symbol(graph, "demo.service.OrderService#helper").key());

		// log(String) and log(String, int): the one-argument call picks exactly one overload
		assertThat(call(calls, "log").targetKey()).isEqualTo(symbols(graph, "demo.service.AuditLog#log").get(0).key());

		// names.size() is a call on a JDK type: left out
		assertThat(calls).noneMatch(e -> "size".equals(e.evidence().get("method")));

		String helper = symbol(graph, "demo.service.OrderService#helper").key();
		assertThat(call(edges(graph, helper, RelationshipType.CALLS), "trim").targetKey())
			.isEqualTo(symbol(graph, "demo.service.Util#trim").key());
	}

	@Test
	void aMethodInheritedFromAThirdPartySupertypeIsUnresolvedNotGuessed() {
		Graph graph = build(new InMemoryWorkspace().add("src/main/java/demo/Svc.java", """
				package demo;
				public class Svc {
					private Repo repo;
					void run() {
						repo.findAll();
					}
				}
				""").add("src/main/java/demo/Repo.java", """
				package demo;
				import org.springframework.data.jpa.repository.JpaRepository;
				public interface Repo extends JpaRepository<String, Long> {
				}
				"""));

		Edge call = call(edges(graph, symbol(graph, "demo.Svc#run").key(), RelationshipType.CALLS), "findAll");

		assertThat(call.resolution()).isEqualTo(EdgeResolution.UNRESOLVED);
		assertThat(call.targetName()).isEqualTo("demo.Repo#findAll");
		assertThat(call.evidence()).containsKey("note");
		Edge extended = edges(graph, symbol(graph, "demo.Repo").key(), RelationshipType.EXTENDS).get(0);
		assertThat(extended.targetName()).isEqualTo("org.springframework.data.jpa.repository.JpaRepository");
		assertThat(extended.resolution()).isEqualTo(EdgeResolution.UNRESOLVED);
	}

	@Test
	void theSameNameInTwoModulesIsAmbiguous() {
		Graph graph = build(new InMemoryWorkspace()
			.add("a/src/main/java/shared/Money.java", "package shared;\npublic class Money {}\n")
			.add("b/src/main/java/shared/Money.java", "package shared;\npublic class Money {}\n")
			.add("a/src/main/java/app/Wallet.java", """
					package app;
					import shared.Money;
					public class Wallet {
						private Money balance;
					}
					"""));

		Edge edge = edges(graph, symbol(graph, "app.Wallet#balance").key(), RelationshipType.FIELD_TYPE).get(0);

		assertThat(edge.resolution()).isEqualTo(EdgeResolution.AMBIGUOUS);
		assertThat(edge.targetKey()).isNull();
		assertThat(edge.evidence().get("candidates")).asList().containsExactly("shared.Money", "shared.Money");
	}

	@Test
	void typeEdgesIncludeTypeArgumentsAndLeaveJdkTypesOut() {
		Graph graph = build(projectFiles());

		List<Edge> returns = edges(graph, symbol(graph, "demo.service.OrderService#fetch").key(),
				RelationshipType.RETURN_TYPE);
		assertThat(returns).isEmpty(); // String

		List<Edge> extended = edges(graph, symbol(graph, "demo.repo.OrderRepository").key(), RelationshipType.EXTENDS);
		assertThat(extended).extracting(Edge::targetName)
			.containsExactly("org.springframework.data.jpa.repository.JpaRepository");

		Graph generic = build(new InMemoryWorkspace().add("src/main/java/demo/Box.java", """
				package demo;
				import java.util.List;
				public class Box<T> {
					private List<Item> items;
					private T content;
					public java.util.Map<String, Item> index() {
						return null;
					}
				}
				""").add("src/main/java/demo/Item.java", "package demo;\npublic class Item {}\n"));
		Edge items = edges(generic, symbol(generic, "demo.Box#items").key(), RelationshipType.FIELD_TYPE).get(0);
		assertThat(items.targetName()).isEqualTo("demo.Item");
		assertThat(items.evidence()).containsEntry("typeArgument", true);
		assertThat(edges(generic, symbol(generic, "demo.Box#content").key(), RelationshipType.FIELD_TYPE))
			.as("a type variable is not a type").isEmpty();
		assertThat(edges(generic, symbol(generic, "demo.Box#index").key(), RelationshipType.RETURN_TYPE))
			.extracting(Edge::targetName)
			.containsExactly("demo.Item");
		assertThat(generic.stats().jdkReferencesSkipped()).isPositive();
	}

	@Test
	void importsOfProjectAndThirdPartyTypesAreRecordedButNotJdkImports() {
		Graph graph = build(projectFiles());

		List<Edge> imports = edges(graph, symbol(graph, "demo.service.OrderService").key(), RelationshipType.IMPORTS);

		assertThat(imports).extracting(Edge::targetName)
			.contains("demo.repo.OrderRepository", "org.springframework.stereotype.Service")
			.doesNotContain("java.util.List");
		assertThat(imports).filteredOn(e -> e.targetName().equals("demo.repo.OrderRepository"))
			.singleElement()
			.extracting(Edge::resolution)
			.isEqualTo(EdgeResolution.RESOLVED);
	}

	@Test
	void anUnparseableFileIsAWarningAndTheRestIsStillModelled() {
		InMemoryWorkspace workspace = projectFiles().add("src/main/java/demo/Broken.java", "package demo;\npublic class {");

		JavaProjectModel model = JavaProjectModel.parse(workspace, workspace.fileInfos(), false, 512 * 1024, f -> ".",
				CancellationToken.NONE);

		assertThat(model.warnings()).singleElement().asString().startsWith("src/main/java/demo/Broken.java");
		assertThat(model.typesNamed("demo.service.OrderService")).hasSize(1);
	}

	@Test
	void testSourcesAreLeftOutByDefault() {
		InMemoryWorkspace workspace = projectFiles().add("src/test/java/demo/OrderServiceTests.java",
				"package demo;\nclass OrderServiceTests {}\n");

		Graph graph = build(workspace);

		assertThat(graph.symbols()).noneMatch(s -> s.fullyQualifiedName().equals("demo.OrderServiceTests"));
	}

	@Test
	void theSymbolCapKeepsTypesAndDropsMembersAndTheirEdges() {
		InMemoryWorkspace workspace = projectFiles();
		JavaProjectModel model = JavaProjectModel.parse(workspace, workspace.fileInfos(), false, 512 * 1024, f -> ".",
				CancellationToken.NONE);
		int types = model.types().size();

		Graph graph = new SymbolGraphBuilder(model, new TypeResolver(model), types, 10_000).build(CancellationToken.NONE);

		assertThat(graph.symbolsTruncated()).isTrue();
		assertThat(graph.symbols()).hasSize(types).allMatch(s -> s.type().isType());
		List<String> keys = graph.symbols().stream().map(Symbol::key).toList();
		assertThat(graph.edges()).allMatch(e -> keys.contains(e.sourceKey()));
	}

	@Test
	void mavenModulesDependOnEachOtherThroughDeclaredDependencies() {
		InMemoryWorkspace workspace = InMemoryWorkspace.fromFixture("maven-multi-module");
		ModuleLayout layout = ModuleLayout.of(MavenPomParser.parse(workspace, workspace.fileInfos()));

		List<ModuleDependency> dependencies = layout.dependencies();

		assertThat(dependencies).singleElement().satisfies(dependency -> {
			assertThat(dependency.sourceModulePath()).isEqualTo("web");
			assertThat(dependency.targetModulePath()).isEqualTo("core");
			assertThat(dependency.evidence()).singleElement().satisfies(evidence -> {
				assertThat(evidence.path()).isEqualTo("web/pom.xml");
				assertThat(evidence.signal()).contains("com.acme:core");
			});
		});
		assertThat(layout.modulePathOf("web/src/main/java/App.java")).isEqualTo("web");
		assertThat(layout.modulePathOf("src/main/java/Main.java")).isEqualTo(".");
	}

	// ── helpers ──────────────────────────────────────────────────────────────────────────────────

	private static InMemoryWorkspace projectFiles() {
		return new InMemoryWorkspace().add("src/main/java/demo/service/OrderService.java", SERVICE)
			.add("src/main/java/demo/service/AuditLog.java", AUDIT)
			.add("src/main/java/demo/service/Util.java", UTIL)
			.add("src/main/java/demo/repo/OrderRepository.java", REPO)
			.add("src/main/java/demo/repo/Order.java", ORDER);
	}

	static Graph build(InMemoryWorkspace workspace) {
		JavaProjectModel model = JavaProjectModel.parse(workspace, workspace.fileInfos(), false, 512 * 1024,
				f -> ".", CancellationToken.NONE);
		return new SymbolGraphBuilder(model, new TypeResolver(model), 50_000, 200_000).build(CancellationToken.NONE);
	}

	static Symbol symbol(Graph graph, String fqn) {
		List<Symbol> matches = symbols(graph, fqn);
		assertThat(matches).as("symbol " + fqn).isNotEmpty();
		return matches.get(0);
	}

	static List<Symbol> symbols(Graph graph, String fqn) {
		return graph.symbols().stream().filter(s -> s.fullyQualifiedName().equals(fqn)).toList();
	}

	static List<Edge> edges(Graph graph, String sourceKey, RelationshipType type) {
		return graph.edges().stream().filter(e -> e.sourceKey().equals(sourceKey) && e.type() == type).toList();
	}

	private static Edge call(List<Edge> calls, String method) {
		Optional<Edge> edge = calls.stream().filter(e -> method.equals(e.evidence().get("method"))).findFirst();
		assertThat(edge).as("call to " + method).isPresent();
		return edge.get();
	}

}
