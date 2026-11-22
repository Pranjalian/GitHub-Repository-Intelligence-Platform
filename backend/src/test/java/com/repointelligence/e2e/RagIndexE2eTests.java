package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.RagE2eTestBase;
import com.repointelligence.support.TarGzBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Step 4.1 end to end: index a snapshot (chunks, keyword index, embeddings), with the deterministic
 * hashing embedder standing in for an embedding model.
 */
class RagIndexE2eTests extends RagE2eTestBase {

	static final String SHA_A = "a1".repeat(20);

	static final String SHA_B = "b2".repeat(20);

	static final String AWS_KEY = "AKIA4HVZ7QK2M9TXR3WB";

	static final String PASSWORD = "Sup3rS3cretPassw0rd";

	static final String PEM_BODY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKj";

	static TarGzBuilder shop() {
		return fixture("spring-boot-architecture")
			.file("src/main/resources/application.properties", """
					spring.datasource.url=jdbc:postgresql://localhost/shop
					spring.datasource.password=%s
					cloud.access-key=%s
					""".formatted(PASSWORD, AWS_KEY))
			.file("src/main/java/com/example/shop/support/Keys.java", """
					package com.example.shop.support;

					public class Keys {

						static final String KEY = \"""
								-----BEGIN PRIVATE KEY-----
								%s
								%s
								-----END PRIVATE KEY-----
								\""";

						public String key() {
							return KEY;
						}

					}
					""".formatted(PEM_BODY, PEM_BODY));
	}

	@Test
	void javaIsChunkedIntoClassAndMethodChunksWithMetadataAndEmbedded() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA_A, shop().build());

		String index = indexAndWait(analysis);

		assertThat((String) JsonPath.read(index, "$.status")).as(index).isEqualTo("COMPLETED");
		assertThat((String) JsonPath.read(index, "$.embeddingStatus")).isEqualTo("COMPLETED");
		assertThat((String) JsonPath.read(index, "$.embeddingProvider")).isEqualTo("hashing");
		assertThat((String) JsonPath.read(index, "$.embeddingModel")).isEqualTo("hash-embed");
		assertThat((String) JsonPath.read(index, "$.embeddingModelVersion")).isEqualTo("v1");
		assertThat((Integer) JsonPath.read(index, "$.embeddingDimensions")).isEqualTo(64);
		assertThat((String) JsonPath.read(index, "$.vectorStore")).isEqualTo("in-process");
		int chunkCount = JsonPath.read(index, "$.chunkCount");
		// the workspace was rebuilt from the tarball for indexing and removed again afterwards
		GITHUB.verify(2, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
				com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/repos/acme/shop/tarball/" + SHA_A)));
		assertThat(WORKSPACE_ROOT.resolve(analysis.snapshotId())).doesNotExist();
		assertThat((Integer) JsonPath.read(index, "$.embeddedChunks")).isEqualTo(chunkCount);

		List<Map<String, Object>> orderService = jdbc.queryForList("""
				SELECT chunk_type, class_name, method_name, language, file_type, module_path, start_line, end_line
				FROM code_chunk WHERE snapshot_id = ?::uuid AND file_path LIKE '%/service/OrderService.java'
				ORDER BY start_line, chunk_type
				""", analysis.snapshotId());
		assertThat(orderService).extracting(r -> r.get("chunk_type") + ":" + r.get("method_name"))
			.containsExactly("CLASS:null", "METHOD:OrderService", "METHOD:findAll", "METHOD:find", "METHOD:create",
					"METHOD:delete", "METHOD:toResponse");
		assertThat(orderService).allSatisfy(r -> {
			assertThat(r.get("class_name")).isEqualTo("com.example.shop.service.OrderService");
			assertThat(r.get("language")).isEqualTo("Java");
			assertThat(r.get("file_type")).isEqualTo("SOURCE");
			assertThat(r.get("module_path")).isEqualTo(".");
		});
		assertThat(orderService.get(0).get("start_line")).isEqualTo(1);
		// tests are chunked as well (the symbol graph does not model them)
		assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunk WHERE snapshot_id = ?::uuid AND file_type = 'TEST'",
				Integer.class, analysis.snapshotId())).isPositive();
		// configuration and documentation
		assertThat(jdbc.queryForList("SELECT DISTINCT chunk_type FROM code_chunk WHERE snapshot_id = ?::uuid", String.class,
				analysis.snapshotId())).contains("CLASS", "METHOD", "CONFIG_SECTION", "DOC_SECTION", "LINE_WINDOW");
		// one provider call per batch of 8 distinct hashes
		assertThat(embedder.batchSizes()).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(8));
		assertThat(embedder.inputs()).hasSize(jdbc.queryForObject(
				"SELECT count(DISTINCT content_hash) FROM code_chunk WHERE snapshot_id = ?::uuid", Integer.class,
				analysis.snapshotId()));
		assertThat(embedder.inputs()).anySatisfy(text -> assertThat(text)
			.startsWith("File: src/main/java/com/example/shop/service/OrderService.java (Java)\nKind: METHOD"));
		// embedding calls are recorded, but never against an analysis run's insight budget
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ai_usage_record WHERE feature = 'embed-chunks' AND run_id IS NULL AND snapshot_id = ?::uuid",
				Integer.class, analysis.snapshotId())).isEqualTo(embedder.batchSizes().size());
	}

	@Test
	void chunkContentIsSecretScrubbedBeforeItIsStoredOrEmbedded() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA_A, shop().build());

		String index = indexAndWait(analysis);

		List<String> contents = jdbc.queryForList("SELECT content FROM code_chunk WHERE snapshot_id = ?::uuid", String.class,
				analysis.snapshotId());
		assertThat(String.join("\n", contents)).doesNotContain(AWS_KEY).doesNotContain(PASSWORD).doesNotContain(PEM_BODY);
		assertThat(String.join("\n", embedder.inputs())).doesNotContain(AWS_KEY).doesNotContain(PASSWORD)
			.doesNotContain(PEM_BODY);
		List<String> redacted = jdbc.queryForList(
				"SELECT DISTINCT file_path FROM code_chunk WHERE snapshot_id = ?::uuid AND redacted ORDER BY 1", String.class,
				analysis.snapshotId());
		assertThat(redacted).contains("src/main/resources/application.properties",
				"src/main/java/com/example/shop/support/Keys.java");
		assertThat(String.join("\n", contents)).contains("spring.datasource.url=jdbc:postgresql://localhost/shop");
		assertThat((List<String>) JsonPath.read(index, "$.warnings")).anySatisfy(w -> assertThat(w).contains("possible secrets"));
	}

	@Test
	void reindexingANewCommitEmbedsOnlyTheChunksWhoseContentChanged() throws Exception {
		Analysis first = analyze("acme", "shop", SHA_A, shop().build());
		indexAndWait(first);
		int firstCalls = embedder.inputs().size();
		embedder.reset();

		String changed = new String(
				getClass().getResourceAsStream("/fixtures/spring-boot-architecture/src/main/java/com/example/shop/service/OrderService.java")
					.readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8)
			.replace("orders.deleteById(id);", "orders.deleteById(id); // audited");
		stubGithub("acme", "shop", SHA_B, shop().file("src/main/java/com/example/shop/service/OrderService.java", changed).build());
		Analysis second = submitAndWait("https://github.com/acme/shop", false);
		assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());

		String index = indexAndWait(second);

		// exactly one chunk (the delete method) has new content; everything else is reused by content hash
		assertThat(embedder.inputs()).singleElement().asString().contains("orders.deleteById(id); // audited");
		assertThat((Integer) JsonPath.read(index, "$.newEmbeddings")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(index, "$.reusedEmbeddings")).isEqualTo(
				(Integer) JsonPath.read(index, "$.chunkCount") - 1);
		assertThat(firstCalls).isGreaterThan(10);
		// both snapshots keep their own chunks
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT snapshot_id) FROM code_chunk", Integer.class)).isEqualTo(2);
	}

	@Test
	void changingTheEmbeddingModelCreatesNewEmbeddingsWithoutTouchingTheOldOnes() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA_A, shop().build());
		indexAndWait(analysis);
		int count = jdbc.queryForObject("SELECT count(*) FROM chunk_embedding WHERE embedding_model_version = 'v1'",
				Integer.class);
		String hash = jdbc.queryForObject("SELECT min(content_hash) FROM chunk_embedding", String.class);
		String v1Before = jdbc.queryForObject("SELECT embedding::text FROM chunk_embedding WHERE content_hash = ? "
				+ "AND embedding_model_version = 'v1'", String.class, hash);

		embedder.use("hash-embed", "v2");
		String index = indexAndWait(analysis);

		assertThat((String) JsonPath.read(index, "$.embeddingModelVersion")).isEqualTo("v2");
		assertThat((Integer) JsonPath.read(index, "$.newEmbeddings")).isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_embedding WHERE embedding_model_version = 'v1'",
				Integer.class)).isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_embedding WHERE embedding_model_version = 'v2'",
				Integer.class)).isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT embedding::text FROM chunk_embedding WHERE content_hash = ? "
				+ "AND embedding_model_version = 'v1'", String.class, hash)).isEqualTo(v1Before);
		assertThat(jdbc.queryForObject("SELECT embedding::text FROM chunk_embedding WHERE content_hash = ? "
				+ "AND embedding_model_version = 'v2'", String.class, hash)).isNotEqualTo(v1Before);
	}

	@Test
	void anUnavailableEmbeddingModelLeavesAKeywordIndexAndSaysWhy() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA_A, shop().build());
		embedder.setUp(false);

		String index = indexAndWait(analysis);

		assertThat((String) JsonPath.read(index, "$.status")).isEqualTo("COMPLETED_WITH_WARNINGS");
		assertThat((String) JsonPath.read(index, "$.embeddingStatus")).isEqualTo("UNAVAILABLE");
		assertThat((String) JsonPath.read(index, "$.embeddingDetail")).contains("stand-in is down")
			.contains("Keyword and symbol search still work");
		assertThat((Integer) JsonPath.read(index, "$.chunkCount")).isPositive();
		assertThat(embedder.inputs()).isEmpty();
	}

	@Test
	void indexingNeedsACompletedAnalysisOfThatRepository() throws Exception {
		Analysis analysis = analyze("acme", "shop", SHA_A, shop().build());

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/v1/repositories/" + java.util.UUID.randomUUID() + "/snapshots/" + analysis.snapshotId() + "/index"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
		assertThat((String) JsonPath.read(getJson(base(analysis) + "/index"), "$.status")).isEqualTo("NOT_INDEXED");
	}

}
