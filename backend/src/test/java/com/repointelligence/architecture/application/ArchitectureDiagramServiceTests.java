package com.repointelligence.architecture.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.architecture.application.ArchitectureDiagramService.Diagrams;
import com.repointelligence.architecture.application.ArchitectureDiagramService.EndpointNode;
import com.repointelligence.architecture.application.ArchitectureDiagramService.EntityLink;
import com.repointelligence.architecture.application.ArchitectureDiagramService.Input;
import com.repointelligence.architecture.application.ArchitectureDiagramService.Link;
import com.repointelligence.architecture.application.ArchitectureDiagramService.ModuleLink;
import com.repointelligence.architecture.application.ArchitectureDiagramService.TypeNode;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArchitectureDiagramServiceTests {

	/** Repository-derived text an attacker controls: a route string and a module directory name. */
	private static final String HOSTILE = "x\"]; click n1 call alert(1); n9[\"<img src=x onerror=alert(1)> &amp; `#` |a|";

	/** A flowchart line: a quoted node, a subgraph, its end, or an edge between synthetic ids. */
	private static final Pattern FLOWCHART_LINE = Pattern.compile(
			"^(flowchart (TB|LR)|\\s+[nmf]\\d+\\[\"[^\"<>]*\"\\]|\\s+subgraph L\\d+\\[\"[^\"<>]*\"\\]|\\s+end"
					+ "|\\s+[nmf]\\d+ --> [nmf]\\d+|\\s+[nmf]\\d+ -->\\|\"[^\"<>]*\"\\| [nmf]\\d+)$");

	private static final Pattern ER_LINE = Pattern.compile("^(erDiagram|\\s+[A-Za-z][A-Za-z0-9_]* \\{|\\s+\\}"
			+ "|\\s+[A-Za-z][A-Za-z0-9_]* (\\|\\|--o\\{|\\}o--\\|\\||\\|\\|--\\|\\||\\}o--o\\{) [A-Za-z][A-Za-z0-9_]* : \"[^\"<>]*\")$");

	private final ArchitectureDiagramService service = new ArchitectureDiagramService();

	private final TypeNode controller = type("OrderController", ArchitectureLayer.CONTROLLER);

	private final TypeNode orderService = type("OrderService", ArchitectureLayer.SERVICE);

	private final TypeNode repository = type("OrderRepository", ArchitectureLayer.REPOSITORY);

	private final TypeNode order = type("Order", ArchitectureLayer.ENTITY);

	private final TypeNode line = type("OrderLine", ArchitectureLayer.ENTITY);

	@Test
	void everyDiagramIsGeneratedFromTheArchitecture() {
		Diagrams diagrams = service.generate(sample());

		assertThat(diagrams.layers()).startsWith("flowchart TB\n")
			.contains("subgraph L1[\"Controller\"]")
			.contains("[\"OrderController\"]")
			.contains("-->|\"manages\"|");
		assertThat(diagrams.apiFlow()).startsWith("flowchart LR\n").contains("[\"GET /api/orders\"]");
		assertThat(diagrams.entities()).startsWith("erDiagram\n")
			.contains("Order ||--o{ OrderLine : \"lines\"")
			.contains("OrderLine }o--|| Order : \"order\"");
		assertThat(diagrams.modules()).contains("[\"web\"]").contains("[\"core\"]");
		assertThat(diagrams.notes()).isEmpty();
		for (String flowchart : List.of(diagrams.layers(), diagrams.apiFlow(), diagrams.modules())) {
			assertThat(flowchart.lines()).allMatch(l -> FLOWCHART_LINE.matcher(l).matches(), "flowchart grammar");
		}
		assertThat(diagrams.entities().lines()).allMatch(l -> ER_LINE.matcher(l).matches(), "erDiagram grammar");
	}

	@Test
	void theApiFlowFollowsControllerServiceRepositoryEntity() {
		String flow = service.generate(sample()).apiFlow();

		// f1 endpoint -> f2 controller -> f3 service -> f4 repository -> f5 entity
		assertThat(flow).contains("f1 --> f2", "f2 --> f3", "f3 --> f4", "f4 --> f5");
	}

	@Test
	void hostileNamesCannotBreakTheSyntaxOrInjectMarkup() {
		TypeNode hostileType = type(HOSTILE, ArchitectureLayer.SERVICE);
		Input input = new Input(List.of(controller, hostileType, order),
				List.of(new Link(controller.id(), hostileType.id())),
				List.of(new EndpointNode("GET", "/a" + HOSTILE, controller.id())),
				List.of(new EntityLink(order.id(), order.id(), EntityRelationshipType.ONE_TO_ONE, HOSTILE)),
				List.of(new ModuleLink(HOSTILE, "core")));

		Diagrams diagrams = service.generate(input);

		for (String flowchart : List.of(diagrams.layers(), diagrams.apiFlow(), diagrams.modules())) {
			assertThat(flowchart.lines()).allMatch(l -> FLOWCHART_LINE.matcher(l).matches(), "flowchart grammar");
			assertThat(flowchart).doesNotContain("<img", "\"];").contains("#60;img");
			assertThat(flowchart.lines()).noneMatch(l -> l.strip().startsWith("click"));
		}
		assertThat(diagrams.entities().lines()).allMatch(l -> ER_LINE.matcher(l).matches(), "erDiagram grammar");
	}

	@Test
	void labelsEscapeEveryCharacterThatEndsAQuoteOrStartsMarkup() {
		assertThat(MermaidWriter.label("a\"b<c>d&e#f")).isEqualTo("a#34;b#60;c#62;d#38;e#35;f");
		assertThat(MermaidWriter.label("line\nbreak")).isEqualTo("line break");
		assertThat(MermaidWriter.label("x".repeat(200))).hasSize(MermaidWriter.LABEL_MAX);
		assertThat(MermaidWriter.identifier("Order<T>", "Entity")).isEqualTo("Order_T_");
		assertThat(MermaidWriter.identifier("1st", "Entity")).isEqualTo("Entity_1st");
	}

	@Test
	void largeArchitecturesAreCappedWithANote() {
		List<TypeNode> types = new ArrayList<>();
		List<Link> links = new ArrayList<>();
		TypeNode hub = type("Hub", ArchitectureLayer.SERVICE);
		types.add(hub);
		for (int i = 0; i < 100; i++) {
			TypeNode t = type("Service" + i, ArchitectureLayer.SERVICE);
			types.add(t);
			links.add(new Link(hub.id(), t.id()));
		}
		Diagrams diagrams = service.generate(new Input(types, links, List.of(), List.of(), List.of()));

		assertThat(diagrams.layers().lines().filter(l -> l.contains("[\"")).count() - 1)
			.as("nodes, without the subgraph title")
			.isEqualTo(ArchitectureDiagramService.MAX_LAYER_NODES);
		assertThat(diagrams.layers()).contains("[\"Hub\"]");
		assertThat(diagrams.notes()).containsExactly("Layer diagram: 40 of 101 classes are shown (the most connected first).");
	}

	@Test
	void nothingToDrawGivesNoDiagram() {
		Diagrams diagrams = service.generate(new Input(List.of(type("Plain", null)), List.of(), List.of(), List.of(), List.of()));

		assertThat(diagrams.layers()).isNull();
		assertThat(diagrams.modules()).isNull();
		assertThat(diagrams.apiFlow()).isNull();
		assertThat(diagrams.entities()).isNull();
	}

	private Input sample() {
		return new Input(List.of(controller, orderService, repository, order, line),
				List.of(new Link(controller.id(), orderService.id()), new Link(orderService.id(), repository.id())),
				List.of(new EndpointNode("GET", "/api/orders", controller.id())),
				List.of(new EntityLink(repository.id(), order.id(), EntityRelationshipType.REPOSITORY_FOR, null),
						new EntityLink(order.id(), line.id(), EntityRelationshipType.ONE_TO_MANY, "lines"),
						new EntityLink(line.id(), order.id(), EntityRelationshipType.MANY_TO_ONE, "order")),
				List.of(new ModuleLink("web", "core")));
	}

	private static TypeNode type(String name, ArchitectureLayer layer) {
		return new TypeNode(UUID.randomUUID(), "com.example." + name, name, layer);
	}

}
