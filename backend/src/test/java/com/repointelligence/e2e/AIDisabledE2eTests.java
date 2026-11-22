package com.repointelligence.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.E2eTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** With the default configuration (AI disabled) every AI endpoint answers honestly and nothing is fetched. */
class AIDisabledE2eTests extends E2eTestBase {

	@Test
	void insightsAreUnavailableWithAReasonAndNothingIsDownloadedOrRecorded() throws Exception {
		String sha = "d4".repeat(20);
		Analysis analysis = analyze("acme", "shop", sha, fixture("spring-boot-architecture").build());
		String architecture = getJson(
				"/api/v1/repositories/" + analysis.repositoryId() + "/snapshots/" + analysis.snapshotId() + "/architecture");
		List<String> ids = JsonPath.read(architecture, "$.layers[*].classes[*].symbolId");

		String json = mockMvc
			.perform(post("/api/v1/repositories/" + analysis.repositoryId() + "/snapshots/" + analysis.snapshotId()
					+ "/insights").contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"EXPLAIN_CLASS\",\"targetId\":\"" + ids.get(0) + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("UNAVAILABLE");
		assertThat((Boolean) JsonPath.read(json, "$.aiGenerated")).isFalse();
		assertThat((String) JsonPath.read(json, "$.reason")).contains("AI is disabled");
		assertThat((Object) JsonPath.read(json, "$.explanation")).isNull();
		assertThat((List<?>) JsonPath.read(json, "$.evidence")).isEmpty();
		// the source was not re-downloaded for nothing
		GITHUB.verify(1, getRequestedFor(urlEqualTo("/repos/acme/shop/tarball/" + sha)));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_record", Integer.class)).isZero();
	}

	@Test
	void theProvidersAndUsageEndpointsSayAiIsDisabled() throws Exception {
		String providers = getJson("/api/v1/ai/providers");

		assertThat((Boolean) JsonPath.read(providers, "$.enabled")).isFalse();
		assertThat((Boolean) JsonPath.read(providers, "$.available")).isFalse();
		assertThat((String) JsonPath.read(providers, "$.unavailableReason")).contains("AI is disabled");
		assertThat((List<String>) JsonPath.read(providers, "$.providers[*].id")).containsExactly("ollama", "gemini",
				"openrouter");
		assertThat((List<Boolean>) JsonPath.read(providers, "$.providers[*].active")).containsOnly(false);
		assertThat((List<String>) JsonPath.read(providers, "$.providers[*].health.status")).containsOnly("UNKNOWN");
		assertThat((Integer) JsonPath.read(getJson("/api/v1/ai/usage"), "$.totalElements")).isZero();
	}

	@Test
	void indexingWorksWithoutAiAndBuildsAKeywordIndexWithoutEmbeddings() throws Exception {
		Analysis analysis = analyze("acme", "shop", "d5".repeat(20), fixture("spring-boot-architecture").build());

		String index = indexAndWait(analysis);

		assertThat((String) JsonPath.read(index, "$.status")).isEqualTo("COMPLETED");
		assertThat((String) JsonPath.read(index, "$.embeddingStatus")).isEqualTo("NOT_CONFIGURED");
		assertThat((String) JsonPath.read(index, "$.embeddingDetail")).contains("AI is disabled");
		assertThat((Integer) JsonPath.read(index, "$.chunkCount")).isPositive();
		assertThat((Integer) JsonPath.read(index, "$.embeddedChunks")).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_embedding", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_record", Integer.class)).isZero();
	}

}
