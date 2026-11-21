package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.E2eTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The architecture of the Spring Boot evaluation fixture through the whole pipeline: analysis, storage
 * and the commit-scoped architecture report. The generated Mermaid diagrams are compared with golden
 * files that the frontend test suite parses with the real Mermaid parser; run with
 * {@code -Dgolden.update=true} to rewrite them after an intended change.
 */
class ArchitectureE2eTests extends E2eTestBase {

	private static final String SHA = "a1".repeat(20);

	private static final Path GOLDEN = Path.of("src/test/resources/golden/spring-boot-architecture");

	@Test
	void theArchitectureReportContainsEverySectionWithEvidenceAndLimitations() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA, fixture("spring-boot-architecture").build());
		assertThat(analysis.status()).isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");

		String json = architecture(analysis);

		assertThat((Boolean) JsonPath.read(json, "$.supported")).isTrue();
		assertThat((String) JsonPath.read(json, "$.status")).isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");
		assertThat((String) JsonPath.read(json, "$.commitSha")).isEqualTo(SHA);
		assertThat((List<String>) JsonPath.read(json, "$.analyzers[*].id")).containsExactly("spring-architecture-analyzer");
		assertThat((List<String>) JsonPath.read(json, "$.analyzers[*].capabilityLevel")).containsExactly("DEEP");

		// layers with evidence and source links
		assertThat((List<String>) JsonPath.read(json, "$.layers[*].layer")).containsExactly("CONTROLLER", "SERVICE",
				"REPOSITORY", "ENTITY", "DTO", "CONFIGURATION", "INFRASTRUCTURE");
		assertThat((List<Integer>) JsonPath.read(json, "$.layers[?(@.layer=='SERVICE')].total")).containsExactly(15);
		assertThat((String) JsonPath.read(json, "$.layers[0].classes[0].sourceUrl"))
			.startsWith("https://github.com/acme/shop/blob/" + SHA + "/src/main/java/com/example/shop/web/OrderController.java#L");
		assertThat((List<String>) JsonPath.read(json, "$.layers[0].classes[0].evidence[*].signal"))
			.containsExactly("@RestController");

		// endpoints, entities, injection and chains
		assertThat((List<String>) JsonPath.read(json, "$.endpoints[*].route")).hasSize(7).contains("/api/orders/{id}");
		assertThat((List<String>) JsonPath.read(json, "$.endpoints[?(@.httpMethod=='POST' && @.route=='/api/orders')].requestType"))
			.containsExactly("com.example.shop.web.CreateOrderRequest");
		assertThat((List<String>) JsonPath.read(json, "$.endpoints[?(@.routeResolved==false)].route"))
			.containsExactly("/api/orders/{ExternalRoutes.LEGACY}");
		assertThat((List<String>) JsonPath.read(json, "$.entities[*].name")).containsExactly("Customer", "Order", "OrderLine");
		assertThat((List<String>) JsonPath.read(json, "$.entities[?(@.name=='Order')].table")).containsExactly("orders");
		assertThat((List<List<String>>) JsonPath.read(json, "$.entities[?(@.name=='Order')].repositories"))
			.containsExactly(List.of("com.example.shop.repository.OrderRepository"));
		assertThat((List<Object>) JsonPath.read(json, "$.injections[?(@.from=='com.example.shop.service.OrderService')]"))
			.hasSize(3);
		List<List<String>> chains = JsonPath.read(json, "$.diChains[*][*].name");
		assertThat(chains).isNotEmpty();
		List<List<Map<String, Object>>> rawChains = JsonPath.read(json, "$.diChains");
		assertThat(rawChains).anySatisfy(chain -> assertThat(chain).extracting(step -> step.get("name"))
			.containsExactly("OrderController", "OrderService", "OrderRepository", "Order"));

		// cycles, patterns, diagrams
		assertThat((List<String>) JsonPath.read(json, "$.cycles[*].rule")).containsExactly("injection-cycle");
		assertThat((List<String>) JsonPath.read(json, "$.cycles[0].members")).containsExactlyInAnyOrder(
				"com.example.shop.service.AuditService", "com.example.shop.service.ReportService");
		assertThat((List<String>) JsonPath.read(json, "$.patterns[*].pattern")).containsExactlyInAnyOrder("ADAPTER",
				"FACTORY", "REPOSITORY", "REPOSITORY", "STRATEGY");
		assertThat((List<String>) JsonPath.read(json, "$.patterns[?(@.pattern=='STRATEGY')].summary"))
			.singleElement()
			.asString()
			.contains("DiscountPolicy");
		assertThat((String) JsonPath.read(json, "$.diagrams.modules")).isNull();

		// limitations: from the analyzer and computed from the graph
		List<String> limitations = JsonPath.read(json, "$.limitations");
		assertThat(limitations).anyMatch(l -> l.contains("no classpath"))
			.anyMatch(l -> l.contains("relationships are not resolved to a symbol of this commit"))
			.anyMatch(l -> l.contains("Reflection, runtime proxies"));
		assertThat(((Number) JsonPath.read(json, "$.summary.unresolvedRelationships")).longValue()).isPositive();

		// the same commit always gives the same diagram source: compare with the golden files
		for (String diagram : List.of("layers", "apiFlow", "entities")) {
			String generated = JsonPath.read(json, "$.diagrams." + diagram);
			assertThat(generated).as(diagram).isNotBlank();
			assertGolden(diagram, generated);
		}
		assertThat((Map<String, Object>) JsonPath.read(json, "$.summary")).containsEntry("endpoints", 7)
			.containsEntry("entities", 3)
			.containsEntry("types", 27);
	}

	@Test
	void aNonJavaRepositoryIsReportedAsUnsupportedAndItsMvp1ReportIsUnchanged() throws Exception {
		Analysis analysis = analyze("acme", "react-app", "b2".repeat(20), fixture("react-typescript").build());

		String json = architecture(analysis);

		assertThat((Boolean) JsonPath.read(json, "$.supported")).isFalse();
		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("NOT_SUPPORTED");
		assertThat((List<String>) JsonPath.read(json, "$.unsupported")).contains("TypeScript: no analyzer pack for TypeScript");
		assertThat((List<Object>) JsonPath.read(json, "$.layers")).isEmpty();
		assertThat((List<Object>) JsonPath.read(json, "$.endpoints")).isEmpty();
		assertThat((String) JsonPath.read(json, "$.diagrams.layers")).isNull();
		assertThat((List<String>) JsonPath.read(json, "$.limitations"))
			.contains("Architecture not supported for TypeScript: no analyzer pack for TypeScript");

		String report = report(analysis);
		assertThat((List<String>) JsonPath.read(report,
				"$.capabilityMatrix[?(@.technology=='TypeScript' && @.category=='ARCHITECTURE')].executionStatus"))
			.containsExactly("NOT_SUPPORTED");
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='ARCHITECTURE')].status"))
			.containsExactly("NOT_SUPPORTED");
		// MVP 1 sections are still there and still analysed
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='SECURITY')].status"))
			.singleElement()
			.isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='DEPENDENCIES')].status"))
			.singleElement()
			.isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");
	}

	@Test
	void theArchitectureOfASnapshotOfAnotherRepositoryIsNotFound() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA, fixture("spring-boot-architecture").build());

		mockMvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/snapshots/" + analysis.snapshotId() + "/architecture"))
			.andExpect(status().isNotFound());
	}

	private String architecture(Analysis analysis) throws Exception {
		return getJson("/api/v1/repositories/" + analysis.repositoryId() + "/snapshots/" + analysis.snapshotId()
				+ "/architecture");
	}

	private static void assertGolden(String name, String generated) throws Exception {
		Path file = GOLDEN.resolve(name + ".mmd");
		if (Boolean.getBoolean("golden.update") || !Files.exists(file)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, generated, StandardCharsets.UTF_8);
		}
		assertThat(generated).as("golden diagram " + file + " (run with -Dgolden.update=true after an intended change)")
			.isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
	}

}
