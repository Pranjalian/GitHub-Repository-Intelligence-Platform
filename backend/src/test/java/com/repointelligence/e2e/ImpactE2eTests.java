package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;

/**
 * Step 5.2: impact analysis through the whole pipeline on the {@code spring-boot-payments} fixture (the plan's
 * {@code PaymentService.calculatePayment()} example): the AST graph, the test references, the picker, the
 * result with confidence and limitations, the isolation between snapshots, and the run without AI.
 */
class ImpactE2eTests extends E2eTestBase {

	static final String SHA = "e5".repeat(20);

	private static final Path GOLDEN = Path.of("src/test/resources/golden");

	String impactBase(Analysis analysis) {
		return base(analysis) + "/impact-analysis";
	}

	String symbolId(Analysis analysis, String query, String signature) throws Exception {
		String json = getJson(impactBase(analysis) + "/symbols?q=" + query);
		List<String> ids = JsonPath.read(json, "$.symbols[?(@.qualifiedName=='" + signature + "')].id");
		assertThat(ids).as("picker result for " + query + ": " + json).hasSize(1);
		return ids.get(0);
	}

	String impact(Analysis analysis, String body) throws Exception {
		return mockMvc.perform(post(impactBase(analysis)).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	Analysis payments() throws Exception {
		Analysis analysis = analyze("acme", "payments", SHA, fixture("spring-boot-payments").build());
		assertThat(analysis.status()).isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");
		return analysis;
	}

	@Test
	void testReferencesAreStoredFromTheTestSources() throws Exception {
		Analysis analysis = payments();

		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT test_symbol, test_method, referenced_type, referenced_member, argument_count, reference_kind
				FROM test_reference WHERE snapshot_id = ?::uuid ORDER BY test_symbol, reference_kind, referenced_member, test_method
				""", analysis.snapshotId());

		assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("test_symbol", "com.example.pay.service.PaymentServiceTests")
			.containsEntry("test_method", "computesTheTotal")
			.containsEntry("referenced_type", "com.example.pay.service.PaymentService")
			.containsEntry("referenced_member", "calculatePayment")
			.containsEntry("argument_count", 1)
			.containsEntry("reference_kind", "CALL"));
		assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("test_symbol", "com.example.pay.service.PaymentServiceTests")
			.containsEntry("test_method", "computesTheTotalWithACoupon")
			.containsEntry("argument_count", 2));
		// a field typed with a production type is a class-level type use; a var initialised with `new` types its calls
		assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("reference_kind", "TYPE_USE")
			.containsEntry("test_symbol", "com.example.pay.service.PaymentServiceTests")
			.containsEntry("test_method", null));
		assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("test_symbol", "com.example.pay.web.OrderControllerTests")
			.containsEntry("referenced_member", "place")
			.containsEntry("reference_kind", "CALL"));
		// the test class itself and library types (Test, Autowired) are never references
		assertThat(rows).noneMatch(row -> String.valueOf(row.get("referenced_type")).startsWith("org.")
				|| String.valueOf(row.get("referenced_type")).endsWith("Tests"));
		assertThat(jdbc.queryForObject("SELECT config_summary ->> 'testReferences' FROM analyzer_execution "
				+ "WHERE run_id = ?::uuid AND analyzer_id = 'java-test-analyzer'", String.class, analysis.runId()))
			.isNotNull();
	}

	@Test
	void changingCalculatePaymentFindsCallersEndpointsRepositoriesAndTests() throws Exception {
		Analysis analysis = payments();
		String target = symbolId(analysis, "PaymentService.calculatePayment",
				"com.example.pay.service.PaymentService#calculatePayment(Order)");

		String json = impact(analysis, "{\"targetSymbolId\":\"" + target + "\",\"depth\":3}");

		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("OK");
		assertThat((String) JsonPath.read(json, "$.commitSha")).isEqualTo(SHA);
		assertThat((String) JsonPath.read(json, "$.target.kind")).isEqualTo("METHOD");
		assertThat((String) JsonPath.read(json, "$.target.layer")).isEqualTo("SERVICE");
		assertThat((String) JsonPath.read(json, "$.target.sourceUrl"))
			.startsWith("https://github.com/acme/payments/blob/" + SHA + "/src/main/java/com/example/pay/service/PaymentService.java#L");
		assertThat((List<String>) JsonPath.read(json, "$.components[*].name")).containsExactlyInAnyOrder(
				"PaymentController.pay(Order)", "OrderService.checkout(Order)",
				"PaymentService.calculatePayment(Order, String)", "OrderController.place(Order)",
				"BillingJob.runNightly(Order)");
		assertThat((List<Integer>) JsonPath.read(json, "$.components[?(@.name=='OrderController.place(Order)')].depth"))
			.containsExactly(2);
		assertThat((List<String>) JsonPath.read(json, "$.components[*].confidence")).containsOnly("DETECTED");
		assertThat((List<String>) JsonPath.read(json, "$.components[?(@.depth==1)].layer"))
			.containsExactlyInAnyOrder("CONTROLLER", "SERVICE", "SERVICE");
		assertThat((List<String>) JsonPath.read(json, "$.endpoints[*].route")).containsExactly("/orders", "/payments");
		assertThat((List<String>) JsonPath.read(json, "$.relatedData[*].qualifiedName"))
			.contains("com.example.pay.repository.PaymentRepository", "com.example.pay.domain.Payment");
		assertThat((List<String>) JsonPath.read(json, "$.tests[*].testSymbol")).startsWith("com.example.pay.service.PaymentServiceTests");
		assertThat((List<String>) JsonPath.read(json,
				"$.tests[?(@.testSymbol=='com.example.pay.service.PaymentServiceTests')].methods[*]"))
			.containsExactly("computesTheTotal");
		assertThat((List<String>) JsonPath.read(json, "$.tests[?(@.testSymbol=='com.example.pay.service.PaymentRefundTests')].relation"))
			.containsExactly("TYPE_REFERENCE");
		assertThat((List<String>) JsonPath.read(json, "$.tests[?(@.testSymbol=='com.example.pay.web.OrderControllerTests')].relation"))
			.containsExactly("AFFECTED_COMPONENT");

		// the risk score is a sum of visible factors and is not called a probability
		assertThat((String) JsonPath.read(json, "$.risk.label")).contains("Heuristic risk score").contains("not a probability");
		List<Integer> points = JsonPath.read(json, "$.risk.factors[*].points");
		assertThat(points.stream().mapToInt(Integer::intValue).sum()).isEqualTo((Integer) JsonPath.read(json, "$.risk.score"));
		assertThat((List<String>) JsonPath.read(json, "$.risk.factors[*].id")).containsExactly("DEPENDENCY_DEPTH",
				"DEPENDENT_COUNT", "PUBLIC_API_EXPOSURE", "SECURITY_RELEVANCE", "TEST_PRESENCE", "COVERAGE");
		assertThat((List<String>) JsonPath.read(json, "$.risk.factors[?(@.id=='COVERAGE')].value")).containsExactly("not available");
		assertThat((String) JsonPath.read(json, "$.risk.level")).isIn("LOW", "MEDIUM", "HIGH");

		// limitations name what the analysis cannot see
		List<String> limitations = JsonPath.read(json, "$.limitations");
		assertThat(limitations).anyMatch(l -> l.contains("Reflection, runtime proxies"))
			.anyMatch(l -> l.contains("Dynamic dispatch is approximated"))
			.anyMatch(l -> l.contains("MockMvc"))
			.anyMatch(l -> l.contains("relationships in this snapshot are unresolved or ambiguous"));
		assertThat((List<String>) JsonPath.read(json, "$.regressionAreas[*].kind")).contains("ENDPOINT", "TEST", "COMPONENT");

		// no AI was asked for: no summary, and the diagram is plain generated source
		assertThat((Object) JsonPath.read(json, "$.aiSummary")).isNull();
		String chain = JsonPath.read(json, "$.diagram.chain");
		assertThat(chain).startsWith("flowchart LR").contains("-->");
		assertGolden("spring-boot-payments", "impactChain", chain);
	}

	@Test
	void changingAnImplementationReachesCallersOfTheInterfaceOnlyAsPossible() throws Exception {
		Analysis analysis = payments();
		String target = symbolId(analysis, "LoyaltyDiscount.apply", "com.example.pay.service.LoyaltyDiscount#apply(Order)");

		String json = impact(analysis, "{\"targetSymbolId\":\"" + target + "\",\"depth\":4}");

		assertThat((List<String>) JsonPath.read(json, "$.components[?(@.relationships[0]=='OVERRIDES')].name"))
			.containsExactly("DiscountPolicy.apply(Order)");
		assertThat((List<String>) JsonPath.read(json, "$.components[?(@.name=='PaymentService.calculatePayment(Order)')].confidence"))
			.containsExactly("POSSIBLE");
		assertThat((List<String>) JsonPath.read(json, "$.components[?(@.name=='OrderController.place(Order)')].confidence"))
			.containsExactly("POSSIBLE");
		assertThat((List<String>) JsonPath.read(json, "$.components[*].confidence")).doesNotContain("DETECTED");
		assertThat((List<String>) JsonPath.read(json, "$.limitations")).anyMatch(l -> l.startsWith("Dynamic dispatch:"));
	}

	@Test
	void aTypeTargetListsDependentTypesAndTheEndpointsOfAffectedControllers() throws Exception {
		Analysis analysis = payments();
		String target = symbolId(analysis, "PaymentService", "com.example.pay.service.PaymentService");

		String json = impact(analysis, "{\"targetSymbolId\":\"" + target + "\"}");

		assertThat((String) JsonPath.read(json, "$.target.kind")).isEqualTo("TYPE");
		assertThat((Integer) JsonPath.read(json, "$.depth")).isEqualTo(3);
		assertThat((List<String>) JsonPath.read(json, "$.components[*].name")).containsExactlyInAnyOrder("PaymentController",
				"OrderService", "OrderController", "BillingJob");
		assertThat((List<String>) JsonPath.read(json, "$.endpoints[*].route")).containsExactlyInAnyOrder("/orders", "/payments");
		assertThat((List<String>) JsonPath.read(json, "$.tests[?(@.relation=='DIRECT_CALL')].testSymbol"))
			.contains("com.example.pay.service.PaymentServiceTests", "com.example.pay.service.PaymentRefundTests");
	}

	@Test
	void theResultIsIdenticalWithAiDisabledAndTheSummaryIsOnlyUnavailable() throws Exception {
		Analysis analysis = payments();
		String target = symbolId(analysis, "PaymentService.calculatePayment",
				"com.example.pay.service.PaymentService#calculatePayment(Order)");
		String plain = impact(analysis, "{\"targetSymbolId\":\"" + target + "\"}");

		String asked = impact(analysis, "{\"targetSymbolId\":\"" + target + "\",\"includeAiSummary\":true,"
				+ "\"changeDescription\":\"round totals to whole cents\"}");

		assertThat((String) JsonPath.read(asked, "$.aiSummary.status")).isEqualTo("UNAVAILABLE");
		assertThat((Boolean) JsonPath.read(asked, "$.aiSummary.aiGenerated")).isFalse();
		assertThat((String) JsonPath.read(asked, "$.aiSummary.detail")).isNotBlank();
		for (String path : List.of("$.components", "$.endpoints", "$.tests", "$.risk", "$.limitations", "$.diagram")) {
			assertThat(JsonPath.<Object>read(asked, path)).as(path).isEqualTo(JsonPath.<Object>read(plain, path));
		}
		String suggestions = mockMvc
			.perform(post(impactBase(analysis) + "/suggestions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"changeDescription\":\"change how payments are calculated\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat((String) JsonPath.read(suggestions, "$.status")).isEqualTo("UNAVAILABLE");
		// the deterministic picker is unaffected
		assertThat((List<String>) JsonPath.read(getJson(impactBase(analysis) + "/symbols?q=billing"), "$.symbols[*].name"))
			.contains("BillingJob");
	}

	@Test
	void theTargetMustBelongToTheSnapshotAndTheDepthIsBounded() throws Exception {
		Analysis analysis = payments();
		String target = symbolId(analysis, "PaymentService", "com.example.pay.service.PaymentService");

		mockMvc.perform(post(impactBase(analysis)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetSymbolId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
		mockMvc.perform(post(impactBase(analysis)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetSymbolId\":\"" + target + "\",\"depth\":99}")).andExpect(status().isBadRequest());
		mockMvc.perform(post(impactBase(analysis)).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		// another repository's id in the path never reaches this snapshot
		mockMvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/snapshots/" + analysis.snapshotId()
				+ "/impact-analysis").contentType(MediaType.APPLICATION_JSON).content("{\"targetSymbolId\":\"" + target + "\"}"))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/snapshots/" + analysis.snapshotId()
				+ "/impact-analysis/symbols?q=a")).andExpect(status().isNotFound());
	}

	@Test
	void aSymbolOfAnotherSnapshotIsNotFoundHere() throws Exception {
		Analysis first = payments();
		Analysis second = analyze("acme", "payments-two", "f6".repeat(20), fixture("spring-boot-payments").build());
		String targetInFirst = symbolId(first, "PaymentService", "com.example.pay.service.PaymentService");

		mockMvc.perform(post(impactBase(second)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetSymbolId\":\"" + targetInFirst + "\"}")).andExpect(status().isNotFound());
	}

	@Test
	void aNonJavaRepositoryHasNothingToPick() throws Exception {
		Analysis analysis = analyze("acme", "react-app", "b7".repeat(20), fixture("react-typescript").build());

		String json = getJson(impactBase(analysis) + "/symbols?q=app");

		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("NOT_SUPPORTED");
		assertThat((String) JsonPath.read(json, "$.reason")).contains("Java");
		assertThat((List<Object>) JsonPath.read(json, "$.symbols")).isEmpty();
	}

	@Test
	void pickerQueriesAreEscapedAndRanked() throws Exception {
		Analysis analysis = payments();

		assertThat((List<Object>) JsonPath.read(getJson(impactBase(analysis) + "/symbols?q=%25"), "$.symbols")).isEmpty();
		assertThat((List<Object>) JsonPath.read(getJson(impactBase(analysis) + "/symbols?q=_"), "$.symbols")).isEmpty();
		List<String> names = JsonPath.read(getJson(impactBase(analysis) + "/symbols?q=PaymentService"), "$.symbols[*].name");
		assertThat(names.get(0)).isEqualTo("PaymentService");
		assertThat(names).anyMatch(n -> n.startsWith("PaymentService.calculatePayment"));
	}

	private static void assertGolden(String fixture, String name, String generated) throws Exception {
		Path file = GOLDEN.resolve(fixture).resolve(name + ".mmd");
		if (Boolean.getBoolean("golden.update") || !Files.exists(file)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, generated, StandardCharsets.UTF_8);
		}
		assertThat(generated).as("golden diagram " + file + " (run with -Dgolden.update=true after an intended change)")
			.isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
	}

}
