package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.rag.indexing.SnapshotIndex;
import com.repointelligence.rag.indexing.SnapshotIndexRepository;
import com.repointelligence.rag.retrieval.ChunkFilter;
import com.repointelligence.rag.retrieval.RagScope;
import com.repointelligence.rag.retrieval.RagScopes;
import com.repointelligence.rag.retrieval.Retrieval;
import com.repointelligence.rag.retrieval.RetrievalService;
import com.repointelligence.rag.retrieval.RetrievedChunk;
import com.repointelligence.support.RagE2eTestBase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Retrieval evaluation (Step 4.2): recall@k and MRR over {@code rag-eval/questions.yml}, measured apart from
 * answer quality. Semantic scores use the deterministic {@code HashingEmbedder} (lexical feature hashing), so
 * they show that the vector path works and fuses correctly, not how good a real embedding model would be.
 * Writes {@code target/rag-eval/report.md}.
 */
class RetrievalEvaluationTests extends RagE2eTestBase {

	private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluationTests.class);

	@Autowired
	private RetrievalService retrieval;

	@Autowired
	private RagScopes scopes;

	@Autowired
	private SnapshotIndexRepository indexes;

	record Question(String id, String question, List<String> expected) {
	}

	record Score(double recall, double mrr, Map<String, String> perQuestion) {
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> dataset() throws Exception {
		try (InputStream in = RetrievalEvaluationTests.class.getResourceAsStream("/rag-eval/questions.yml")) {
			return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
		}
	}

	@SuppressWarnings("unchecked")
	static List<Question> questions(Map<String, Object> data) {
		List<Question> result = new ArrayList<>();
		for (Map<String, Object> q : (List<Map<String, Object>>) data.get("questions")) {
			result.add(new Question((String) q.get("id"), (String) q.get("question"), (List<String>) q.get("expected")));
		}
		return result;
	}

	private record Indexed(RagScope scope, SnapshotIndex index) {
	}

	private Indexed indexFixture(String fixture) throws Exception {
		Analysis analysis = analyze("eval", "shop", "e7".repeat(20), fixture(fixture).build());
		indexAndWait(analysis);
		UUID repositoryId = UUID.fromString(analysis.repositoryId());
		UUID snapshotId = UUID.fromString(analysis.snapshotId());
		return new Indexed(scopes.resolve(repositoryId, snapshotId),
				indexes.findBySnapshotIdAndRepositoryId(snapshotId, repositoryId).orElseThrow());
	}

	private Score score(Indexed indexed, List<Question> questions, int k, RetrievalService.Sources sources) {
		double recall = 0;
		double mrr = 0;
		Map<String, String> perQuestion = new LinkedHashMap<>();
		for (Question q : questions) {
			Retrieval result = retrieval.retrieve(indexed.scope(), indexed.index(), q.question(), ChunkFilter.NONE, k, true,
					sources);
			List<String> files = result.chunks().stream().map(c -> c.chunk().filePath()).toList();
			Set<String> found = new LinkedHashSet<>(files);
			found.retainAll(q.expected());
			double r = (double) found.size() / q.expected().size();
			double rr = 0;
			for (int i = 0; i < files.size(); i++) {
				if (q.expected().contains(files.get(i))) {
					rr = 1.0 / (i + 1);
					break;
				}
			}
			recall += r;
			mrr += rr;
			perQuestion.put(q.id(), String.format(Locale.ROOT, "recall %.2f, rr %.2f, top: %s", r, rr,
					files.stream().map(f -> f.substring(f.lastIndexOf('/') + 1)).toList()));
		}
		return new Score(recall / questions.size(), mrr / questions.size(), perQuestion);
	}

	@Test
	@SuppressWarnings("unchecked")
	void retrievalQualityStaysAboveTheAgreedBaseline() throws Exception {
		Map<String, Object> data = dataset();
		List<Question> questions = questions(data);
		int k = (Integer) data.get("k");
		Indexed indexed = indexFixture((String) data.get("fixture"));

		Map<String, RetrievalService.Sources> modes = new LinkedHashMap<>();
		modes.put("keyword-symbol", new RetrievalService.Sources(true, true, false));
		modes.put("semantic", new RetrievalService.Sources(false, false, true));
		modes.put("hybrid", RetrievalService.Sources.ALL);
		Map<String, Score> scores = new LinkedHashMap<>();
		modes.forEach((name, sources) -> scores.put(name, score(indexed, questions, k, sources)));

		StringBuilder report = new StringBuilder("# Retrieval evaluation (" + questions.size() + " questions, k=" + k
				+ ")\n\n| Mode | recall@" + k + " | MRR |\n|---|---|---|\n");
		scores.forEach((name, s) -> report.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f |%n", name, s.recall(), s.mrr())));
		report.append("\nSemantic uses the deterministic hashing embedder of the tests, not a real model.\n");
		scores.forEach((name, s) -> {
			report.append("\n## ").append(name).append("\n\n");
			s.perQuestion().forEach((id, line) -> report.append("- ").append(id).append(": ").append(line).append('\n'));
		});
		Path out = Path.of("target", "rag-eval", "report.md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report, StandardCharsets.UTF_8);
		log.info("Retrieval evaluation\n{}", report);

		Map<String, Map<String, Number>> thresholds = (Map<String, Map<String, Number>>) data.get("thresholds");
		thresholds.forEach((mode, limits) -> {
			Score s = scores.get(mode);
			assertThat(s.recall()).as(mode + " recall@" + k + "\n" + report).isGreaterThanOrEqualTo(limits.get("recall").doubleValue());
			assertThat(s.mrr()).as(mode + " MRR\n" + report).isGreaterThanOrEqualTo(limits.get("mrr").doubleValue());
		});
		// No "hybrid >= keyword" assertion: with the lexical stand-in embedder, semantic ranks add noise; whether
		// fusion helps must be judged with a real embedding model (see the MVP 4 completion report).
	}

	@Test
	void semanticSearchAloneReturnsRelevantChunksForFixtureQueries() throws Exception {
		Indexed indexed = indexFixture("spring-boot-architecture");

		RetrievalService.Sources semanticOnly = new RetrievalService.Sources(false, false, true);
		Retrieval refunds = retrieval.retrieve(indexed.scope(), indexed.index(),
				"How does the legacy payment bridge refund a payment?", ChunkFilter.NONE, 3, true, semanticOnly);
		Retrieval endpoints = retrieval.retrieve(indexed.scope(), indexed.index(),
				"Which REST endpoints does the OrderController expose?", ChunkFilter.NONE, 3, true, semanticOnly);

		assertThat(refunds.semantic()).isTrue();
		assertThat(refunds.keywordHits()).isZero();
		assertThat(refunds.chunks()).extracting(RetrievedChunk::semanticRank).doesNotContainNull();
		assertThat(refunds.chunks().get(0).chunk().filePath()).endsWith("LegacyPaymentBridge.java");
		assertThat(refunds.chunks().get(0).similarity()).isBetween(0.0, 1.0);
		assertThat(endpoints.chunks().get(0).chunk().filePath()).endsWith("OrderController.java");
	}

}
