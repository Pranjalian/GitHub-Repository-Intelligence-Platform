package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.RagE2eTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Step 4.2: deterministic search through the API, with and without an index, with and without embeddings. */
class RagSearchE2eTests extends RagE2eTestBase {

	static final String SHA = "c4".repeat(20);

	/** Parameters as name, value pairs; MockMvc encodes them (no hand-written escapes). */
	private String search(Analysis analysis, String... params) throws Exception {
		return mockMvc.perform(request(analysis, params)).andReturn().getResponse().getContentAsString();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(Analysis analysis,
			String... params) {
		var builder = get(base(analysis) + "/search");
		for (int i = 0; i < params.length; i += 2) {
			builder.param(params[i], params[i + 1]);
		}
		return builder;
	}

	@Test
	void symbolSearchFindsExactIdentifiersRightAfterTheAnalysis() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA, fixture("spring-boot-architecture").build());

		String type = search(analysis, "type", "symbol", "q", "OrderService");
		assertThat((String) JsonPath.read(type, "$.status")).isEqualTo("OK");
		assertThat((String) JsonPath.read(type, "$.source")).isEqualTo("SYMBOL_GRAPH");
		assertThat((String) JsonPath.read(type, "$.index.status")).isEqualTo("NOT_INDEXED");
		assertThat((String) JsonPath.read(type, "$.results[0].qualifiedName")).isEqualTo("com.example.shop.service.OrderService");
		assertThat((String) JsonPath.read(type, "$.results[0].symbolType")).isEqualTo("CLASS");
		assertThat((String) JsonPath.read(type, "$.results[0].sourceUrl"))
			.startsWith("https://github.com/acme/shop/blob/" + SHA + "/src/main/java/com/example/shop/service/OrderService.java#L");
		// a field with the same name in another case (OrderController#orderService) matches too, after the exact-case type
		assertThat((List<String>) JsonPath.read(type, "$.results[*].qualifiedName"))
			.containsExactly("com.example.shop.service.OrderService", "com.example.shop.web.OrderController#orderService");

		// case-insensitive, qualified and Type.member forms
		assertThat((List<String>) JsonPath.read(search(analysis, "type", "symbol", "q", "orderservice"), "$.results[*].qualifiedName"))
			.contains("com.example.shop.service.OrderService");
		assertThat((List<String>) JsonPath.read(search(analysis, "type", "symbol", "q", "OrderService.create"),
				"$.results[*].qualifiedName")).containsExactly("com.example.shop.service.OrderService#create");
		assertThat((List<String>) JsonPath.read(
				search(analysis, "type", "symbol", "q", "com.example.shop.web.OrderController#create"), "$.results[*].qualifiedName"))
			.containsExactly("com.example.shop.web.OrderController#create");
		// exact identifiers only: no prefix or fuzzy matches
		assertThat((List<?>) JsonPath.read(search(analysis, "type", "symbol", "q", "OrderServ"), "$.results")).isEmpty();
	}

	@Test
	void textSearchNeedsTheIndexAndThenMatchesAllWordsWithFilters() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA, fixture("spring-boot-architecture").build());

		String before = search(analysis, "q", "discount");
		assertThat((String) JsonPath.read(before, "$.status")).isEqualTo("NOT_INDEXED");
		assertThat((String) JsonPath.read(before, "$.reason")).contains("index this snapshot");

		indexAndWait(analysis);

		String discount = search(analysis, "q", "discount apply");
		assertThat((String) JsonPath.read(discount, "$.status")).isEqualTo("OK");
		List<String> files = JsonPath.read(discount, "$.results[*].filePath");
		assertThat(files).isNotEmpty()
			.allSatisfy(f -> assertThat(f).matches(".*((Discount|DiscountPolicy|OrderService)\\.java|EXPECTED\\.md)"));
		assertThat((String) JsonPath.read(discount, "$.results[0].snippet")).containsIgnoringCase("apply");
		assertThat((String) JsonPath.read(discount, "$.results[0].sourceUrl")).contains("/blob/" + SHA + "/");
		// words also match by a light stem (policies -> policy), identifiers by their camelCase parts
		assertThat((List<String>) JsonPath.read(search(analysis, "q", "discount policies"), "$.results[*].filePath"))
			.contains("src/main/java/com/example/shop/service/DiscountPolicy.java");
		assertThat((List<String>) JsonPath.read(search(analysis, "q", "discountPolicies"), "$.results[*].filePath"))
			.contains("src/main/java/com/example/shop/service/OrderService.java");

		// filters
		assertThat((List<String>) JsonPath.read(search(analysis, "q", "OrderService", "fileType", "TEST"), "$.results[*].filePath"))
			.containsOnly("src/test/java/com/example/shop/OrderServiceTests.java");
		assertThat((List<String>) JsonPath.read(search(analysis, "q", "OrderService", "language", "Markdown"), "$.results[*].chunkType"))
			.containsOnly("DOC_SECTION");
		assertThat((List<?>) JsonPath.read(search(analysis, "q", "OrderService", "module", "nowhere"), "$.results")).isEmpty();
	}

	@Test
	void textSearchWorksWhenNoEmbeddingsCanBeMade() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA, fixture("spring-boot-architecture").build());
		embedder.setUp(false);
		String index = indexAndWait(analysis);
		assertThat((String) JsonPath.read(index, "$.embeddingStatus")).isEqualTo("UNAVAILABLE");

		String result = search(analysis, "q", "refund");

		assertThat((String) JsonPath.read(result, "$.status")).isEqualTo("OK");
		assertThat((List<String>) JsonPath.read(result, "$.results[*].filePath"))
			.contains("src/main/java/com/example/shop/service/LegacyPaymentBridge.java");
	}

	@Test
	void malformedQueriesAreRejected() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA, fixture("spring-boot-architecture").build());

		mockMvc.perform(request(analysis, "q", " ")).andExpect(status().isBadRequest());
		mockMvc.perform(request(analysis, "q", "x", "type", "fuzzy")).andExpect(status().isBadRequest());
		mockMvc.perform(request(analysis, "type", "symbol", "q", "a b")).andExpect(status().isBadRequest());
		mockMvc.perform(request(analysis, "q", "&|!()")).andExpect(status().isBadRequest());
	}

}
