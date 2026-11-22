package com.repointelligence.rag.retrieval;

import com.repointelligence.rag.RagProperties;
import com.repointelligence.rag.chunking.ChunkType;
import com.repointelligence.rag.embedding.EmbeddingService;
import com.repointelligence.rag.indexing.ChunkStore;
import com.repointelligence.rag.indexing.EmbeddingStatus;
import com.repointelligence.rag.indexing.SnapshotIndex;
import com.repointelligence.rag.indexing.StoredChunk;
import com.repointelligence.rag.vectorstore.VectorStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval over one snapshot: keyword (full-text, any meaningful word), symbol (identifiers in the
 * question that name a symbol exactly) and semantic (cosine similarity of embeddings, when the index has
 * them and the same embedding model is available), fused with reciprocal rank fusion:
 * {@code score = sum over sources of 1 / (rrf-k + rank)}. Ties are broken by path and line, so the same
 * question on the same commit always gives the same chunks. Every source is bound to the snapshot and the
 * repository in the repository layer ({@link ChunkStore}, {@link VectorStore}, {@link SymbolLookup}).
 */
@Service
public class RetrievalService {

	private final ChunkStore chunks;

	private final SymbolLookup symbols;

	private final VectorStore vectors;

	private final EmbeddingService embeddings;

	private final RagProperties.Retrieval settings;

	public RetrievalService(ChunkStore chunks, SymbolLookup symbols, VectorStore vectors, EmbeddingService embeddings,
			RagProperties properties) {
		this.chunks = chunks;
		this.symbols = symbols;
		this.vectors = vectors;
		this.embeddings = embeddings;
		this.settings = properties.retrieval();
	}

	/** Which sources to use; all by default. Lets the evaluation measure each one. */
	public record Sources(boolean keyword, boolean symbol, boolean semantic) {

		public static final Sources ALL = new Sources(true, true, true);

	}

	public Retrieval retrieve(RagScope scope, SnapshotIndex index, String question, ChunkFilter filter, int topK,
			boolean hostedPermitted) {
		return retrieve(scope, index, question, filter, topK, hostedPermitted, Sources.ALL);
	}

	public Retrieval retrieve(RagScope scope, SnapshotIndex index, String question, ChunkFilter filter, int topK,
			boolean hostedPermitted, Sources sources) {
		QueryTerms terms = QueryTerms.of(question);
		int candidates = Math.max(topK, settings.candidates());

		// keyword
		List<UUID> keyword = new ArrayList<>();
		String any = terms.anyOf();
		if (sources.keyword() && any != null) {
			chunks.keyword(scope.snapshotId(), scope.repositoryId(), any, filter, candidates)
				.forEach(hit -> keyword.add(hit.id()));
		}

		// symbol: identifiers of the question that name a symbol exactly
		List<UUID> symbol = new ArrayList<>();
		Set<String> matchedSymbols = new LinkedHashSet<>();
		if (sources.symbol()) {
			symbolHits(scope, terms, filter, candidates, symbol, matchedSymbols);
		}

		// semantic
		List<UUID> semantic = new ArrayList<>();
		Map<UUID, Double> similarity = new HashMap<>();
		boolean semanticUsed = false;
		String semanticReason;
		if (!sources.semantic()) {
			semanticReason = "not requested";
		}
		else if (index == null || (index.getEmbeddingStatus() != EmbeddingStatus.COMPLETED
				&& index.getEmbeddingStatus() != EmbeddingStatus.PARTIAL)) {
			semanticReason = index == null ? "the snapshot is not indexed"
					: "the index has no embeddings (" + index.getEmbeddingStatus() + ")";
		}
		else {
			EmbeddingService.QueryVector query = embeddings.embedQuery(question, index.getEmbeddingModel(),
					index.getEmbeddingModelVersion(), scope.repositoryId(), scope.snapshotId(), hostedPermitted);
			if (!query.available()) {
				semanticReason = query.unavailableReason();
			}
			else {
				VectorStore.VectorSearch search = vectors.nearest(scope.snapshotId(), scope.repositoryId(),
						index.getEmbeddingModel(), index.getEmbeddingModelVersion(), query.vector(), filter, candidates);
				if (search.unavailableReason() != null) {
					semanticReason = search.unavailableReason();
				}
				else {
					semanticUsed = true;
					semanticReason = null;
					search.hits().forEach(hit -> {
						semantic.add(hit.chunkId());
						similarity.put(hit.chunkId(), hit.similarity());
					});
				}
			}
		}

		// reciprocal rank fusion
		Map<UUID, double[]> fused = new LinkedHashMap<>();
		Map<UUID, Integer[]> ranks = new HashMap<>();
		addRanks(keyword, 0, fused, ranks);
		addRanks(symbol, 1, fused, ranks);
		addRanks(semantic, 2, fused, ranks);
		Map<UUID, StoredChunk> byId = chunks.byIds(scope.snapshotId(), scope.repositoryId(), fused.keySet());
		List<RetrievedChunk> ranked = new ArrayList<>();
		for (Map.Entry<UUID, double[]> entry : fused.entrySet()) {
			StoredChunk chunk = byId.get(entry.getKey());
			if (chunk == null) {
				continue;
			}
			Integer[] r = ranks.get(entry.getKey());
			ranked.add(new RetrievedChunk(chunk, entry.getValue()[0], r[0], r[1], r[2], similarity.get(entry.getKey())));
		}
		ranked.sort(Comparator.comparingDouble((RetrievedChunk c) -> -c.score())
			.thenComparing(c -> c.chunk().filePath())
			.thenComparingInt(c -> c.chunk().startLine())
			.thenComparing(c -> c.chunk().chunkType()));
		return new Retrieval(ranked.stream().limit(topK).toList(), terms.meaningful(), List.copyOf(matchedSymbols),
				keyword.size(), symbol.size(), semantic.size(), semanticUsed, semanticReason);
	}

	private void addRanks(List<UUID> list, int source, Map<UUID, double[]> fused, Map<UUID, Integer[]> ranks) {
		for (int i = 0; i < list.size(); i++) {
			UUID id = list.get(i);
			Integer[] r = ranks.computeIfAbsent(id, k -> new Integer[3]);
			if (r[source] != null) {
				continue;
			}
			r[source] = i + 1;
			fused.computeIfAbsent(id, k -> new double[1])[0] += 1.0 / (settings.rrfK() + i + 1);
		}
	}

	private void symbolHits(RagScope scope, QueryTerms terms, ChunkFilter filter, int candidates, List<UUID> out,
			Set<String> matched) {
		boolean graph = symbols.hasSymbols(scope.snapshotId(), scope.runId());
		for (String token : terms.meaningful()) {
			if (token.length() < 3 || out.size() >= candidates) {
				continue;
			}
			if (graph) {
				for (SymbolLookup.SymbolRow row : symbols.find(scope.snapshotId(), scope.runId(), token, filter.module(), 5)) {
					if (row.startLine() == null || row.endLine() == null) {
						continue;
					}
					// an exact identifier match: a question word that is only a common word must match case-sensitively
					if (!row.simpleName().equals(token) && !looksLikeIdentifier(token)
							&& !row.simpleName().equalsIgnoreCase(token)) {
						continue;
					}
					for (StoredChunk chunk : chunks.overlapping(scope.snapshotId(), scope.repositoryId(), row.filePath(),
							row.startLine(), row.endLine())) {
						boolean own = row.typeName().equals(chunk.className())
								&& (row.isType() ? chunk.chunkType() == ChunkType.CLASS
										: chunk.chunkType() == ChunkType.METHOD && row.simpleName().equals(chunk.methodName()));
						if (own && !out.contains(chunk.id())) {
							out.add(chunk.id());
							matched.add(row.isType() ? row.simpleName() : row.typeName().substring(row.typeName().lastIndexOf('.') + 1)
									+ "." + row.simpleName());
						}
					}
				}
			}
			else {
				for (StoredChunk chunk : chunks.byName(scope.snapshotId(), scope.repositoryId(), token, filter, 5)) {
					if (!out.contains(chunk.id())) {
						out.add(chunk.id());
						matched.add(chunk.label());
					}
				}
			}
		}
	}

	/** camelCase, PascalCase, snake_case or dotted: clearly meant as code, not as an English word. */
	static boolean looksLikeIdentifier(String token) {
		return token.contains("_") || token.chars().skip(1).anyMatch(Character::isUpperCase)
				|| Character.isUpperCase(token.charAt(0));
	}

}
