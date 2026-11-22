package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.rag.indexing.ChunkStore;
import com.repointelligence.rag.indexing.SnapshotIndexRepository;
import com.repointelligence.rag.retrieval.ChunkFilter;
import com.repointelligence.rag.retrieval.RagScope;
import com.repointelligence.rag.retrieval.RagScopes;
import com.repointelligence.rag.retrieval.Retrieval;
import com.repointelligence.rag.retrieval.RetrievalService;
import com.repointelligence.rag.vectorstore.VectorStore;
import com.repointelligence.support.RagE2eTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * No cross-repository or cross-commit leakage (MVP 4 exit criterion): two repositories and two commits of one of
 * them are indexed; every search, retrieval source and the vector store only ever return chunks of the scope asked for.
 */
class RagIsolationE2eTests extends RagE2eTestBase {

	@Autowired
	private RetrievalService retrieval;

	@Autowired
	private RagScopes scopes;

	@Autowired
	private SnapshotIndexRepository indexes;

	@Autowired
	private ChunkStore chunks;

	@Autowired
	private VectorStore vectors;

	static String marker(String token) {
		return "package com.example.shop.support;\n\n/** " + token + " lives here. */\npublic class Marker {\n\n"
				+ "\tpublic String " + token + "() {\n\t\treturn \"" + token + "\";\n\t}\n\n}\n";
	}

	@Test
	void searchesAndRetrievalNeverReturnAnotherSnapshotsOrRepositorysChunks() throws Exception {
		Analysis a1 = analyze("acme", "shop", "a1".repeat(20), fixture("spring-boot-architecture")
			.file("src/main/java/com/example/shop/support/Marker.java", marker("zebracornAlpha"))
			.build());
		Analysis a2 = analyze("acme", "shop", "a2".repeat(20), fixture("spring-boot-architecture")
			.file("src/main/java/com/example/shop/support/Marker.java", marker("zebracornAlphaTwo"))
			.build());
		Analysis b = analyze("other", "store", "b1".repeat(20), fixture("spring-boot-architecture")
			.file("src/main/java/com/example/shop/support/Marker.java", marker("zebracornBeta"))
			.build());
		assertThat(a2.repositoryId()).isEqualTo(a1.repositoryId());
		indexAndWait(a1);
		indexAndWait(a2);
		indexAndWait(b);

		// text search
		assertThat(files(a1, "q=zebracornAlpha")).isNotEmpty().containsOnly("src/main/java/com/example/shop/support/Marker.java");
		assertThat(files(a1, "q=zebracornBeta")).isEmpty();
		assertThat(files(a1, "q=zebracornAlphaTwo")).isEmpty();
		assertThat(files(b, "q=zebracornAlpha")).isEmpty();
		// symbol search
		assertThat((List<?>) JsonPath.read(getJson(base(a1) + "/search?type=symbol&q=zebracornBeta"), "$.results")).isEmpty();
		assertThat((List<?>) JsonPath.read(getJson(base(b) + "/search?type=symbol&q=zebracornBeta"), "$.results")).hasSize(1);

		// hybrid retrieval with all sources (keyword, symbol, semantic) for terms that exist elsewhere only
		UUID repoA = UUID.fromString(a1.repositoryId());
		UUID snapA1 = UUID.fromString(a1.snapshotId());
		RagScope scope = scopes.resolve(repoA, snapA1);
		Retrieval result = retrieval.retrieve(scope, indexes.findBySnapshotIdAndRepositoryId(snapA1, repoA).orElseThrow(),
				"zebracornBeta zebracornAlphaTwo Marker", ChunkFilter.NONE, 20, true);
		assertThat(result.semantic()).isTrue();
		List<UUID> ids = result.chunks().stream().map(c -> c.chunk().id()).toList();
		assertThat(ids).isNotEmpty();
		assertThat(snapshotsOf(ids)).containsOnly(snapA1);
		assertThat(result.chunks()).allSatisfy(c -> assertThat(c.chunk().content()).doesNotContain("zebracornBeta")
			.doesNotContain("zebracornAlphaTwo"));

		// the vector store, with a query vector of another snapshot's chunk
		float[] betaVector = embedder.vector("zebracornBeta");
		assertThat(snapshotsOf(vectors.nearest(snapA1, repoA, "hash-embed", "v1", betaVector, ChunkFilter.NONE, 50)
			.hits()
			.stream()
			.map(VectorStore.VectorHit::chunkId)
			.toList())).containsOnly(snapA1);

		// chunk ids of another snapshot or repository are not returned, even when asked for directly
		List<UUID> betaIds = jdbc.queryForList("SELECT id FROM code_chunk WHERE snapshot_id = ?::uuid", UUID.class,
				b.snapshotId());
		assertThat(chunks.byIds(snapA1, repoA, betaIds)).isEmpty();
		assertThat(chunks.byIds(UUID.fromString(b.snapshotId()), repoA, betaIds)).isEmpty();

		// a snapshot only exists under its own repository
		mockMvc.perform(get("/api/v1/repositories/" + b.repositoryId() + "/snapshots/" + a1.snapshotId() + "/search?q=x"))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/repositories/" + b.repositoryId() + "/snapshots/" + a1.snapshotId() + "/index"))
			.andExpect(status().isNotFound());
	}

	private List<String> files(Analysis analysis, String query) throws Exception {
		return JsonPath.read(getJson(base(analysis) + "/search?" + query), "$.results[*].filePath");
	}

	private List<UUID> snapshotsOf(List<UUID> chunkIds) {
		return chunkIds.stream()
			.map(id -> jdbc.queryForObject("SELECT snapshot_id FROM code_chunk WHERE id = ?", UUID.class, id))
			.distinct()
			.toList();
	}

}
