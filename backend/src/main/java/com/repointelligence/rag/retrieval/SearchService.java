package com.repointelligence.rag.retrieval;

import com.repointelligence.common.exception.BadRequestException;
import com.repointelligence.rag.RagProperties;
import com.repointelligence.rag.api.IndexStatusDTO;
import com.repointelligence.rag.api.SearchResponseDTO;
import com.repointelligence.rag.api.SearchResultDTO;
import com.repointelligence.rag.chunking.ChunkType;
import com.repointelligence.rag.indexing.ChunkStore;
import com.repointelligence.rag.indexing.SnapshotIndexService;
import com.repointelligence.rag.indexing.StoredChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Deterministic search, usable without any model: exact symbol lookup in the symbol graph and full-text
 * search in the snapshot's keyword index, with language, module and file-type filters. Every query is
 * bound to one snapshot of one repository.
 */
@Service
public class SearchService {

	/** A symbol query is an identifier, optionally qualified with dots or {@code Type#member}. */
	private static final Pattern SYMBOL = Pattern
		.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:[.#][A-Za-z_$][A-Za-z0-9_$]*)*");

	static final int MAX_QUERY = 500;

	private final RagScopes scopes;

	private final SnapshotIndexService indexes;

	private final ChunkStore chunks;

	private final SymbolLookup symbols;

	private final RagProperties.Retrieval settings;

	public SearchService(RagScopes scopes, SnapshotIndexService indexes, ChunkStore chunks, SymbolLookup symbols,
			RagProperties properties) {
		this.scopes = scopes;
		this.indexes = indexes;
		this.chunks = chunks;
		this.symbols = symbols;
		this.settings = properties.retrieval();
	}

	public SearchResponseDTO search(UUID repositoryId, UUID snapshotId, String query, String type, ChunkFilter filter,
			Integer limit) {
		if (query == null || query.isBlank()) {
			throw new BadRequestException("q must not be empty.");
		}
		if (query.length() > MAX_QUERY) {
			throw new BadRequestException("q must be at most " + MAX_QUERY + " characters.");
		}
		String kind = type == null || type.isBlank() ? "text" : type.trim().toLowerCase(Locale.ROOT);
		if (!kind.equals("text") && !kind.equals("symbol")) {
			throw new BadRequestException("type must be symbol or text.");
		}
		int max = limit == null ? settings.maxSearchResults() : Math.max(1, Math.min(limit, settings.maxSearchResults()));
		RagScope scope = scopes.resolve(repositoryId, snapshotId);
		IndexStatusDTO index = indexes.status(repositoryId, snapshotId);
		return kind.equals("symbol") ? symbol(scope, query.trim(), filter, max, index)
				: text(scope, query, filter, max, index);
	}

	private SearchResponseDTO text(RagScope scope, String query, ChunkFilter filter, int max, IndexStatusDTO index) {
		QueryTerms terms = QueryTerms.of(query);
		if (terms.isEmpty()) {
			throw new BadRequestException("q has no searchable words (letters, digits or underscores).");
		}
		if (!index.status().searchable()) {
			return new SearchResponseDTO(query, "TEXT", "NOT_INDEXED",
					"Text search needs the snapshot's index; index this snapshot first. Symbol search works without it.",
					scope.commitSha(), "KEYWORD_INDEX", List.of(), terms.tokens(), index);
		}
		List<ChunkStore.Hit> hits = chunks.keyword(scope.snapshotId(), scope.repositoryId(), terms.allOf(), filter, max);
		Map<UUID, StoredChunk> byId = chunks.byIds(scope.snapshotId(), scope.repositoryId(),
				hits.stream().map(ChunkStore.Hit::id).toList());
		List<String> words = terms.highlightWords();
		List<SearchResultDTO> results = new ArrayList<>();
		for (ChunkStore.Hit hit : hits) {
			StoredChunk chunk = byId.get(hit.id());
			if (chunk != null) {
				results.add(chunkResult(scope, chunk, round(hit.score()), Snippets.around(chunk.content(), words)));
			}
		}
		return new SearchResponseDTO(query, "TEXT", "OK", null, scope.commitSha(), "KEYWORD_INDEX", results,
				terms.tokens(), index);
	}

	private SearchResponseDTO symbol(RagScope scope, String query, ChunkFilter filter, int max, IndexStatusDTO index) {
		if (!SYMBOL.matcher(query).matches()) {
			throw new BadRequestException(
					"A symbol query is one identifier, optionally qualified (e.g. OwnerController, Owner.getPets, org.x.Owner#getPets).");
		}
		boolean indexed = index.status().searchable();
		if (symbols.hasSymbols(scope.snapshotId(), scope.runId())) {
			if (filter.language() != null && !filter.language().equalsIgnoreCase("java")) {
				return new SearchResponseDTO(query, "SYMBOL", "OK", "The symbol graph covers Java only.", scope.commitSha(),
						"SYMBOL_GRAPH", List.of(), List.of(query), index);
			}
			List<SearchResultDTO> results = new ArrayList<>();
			for (SymbolLookup.SymbolRow row : symbols.find(scope.snapshotId(), scope.runId(), query, filter.module(), max)) {
				String snippet = null;
				boolean redacted = false;
				if (indexed && row.startLine() != null && row.endLine() != null) {
					StoredChunk chunk = chunkOf(scope, row);
					if (chunk != null) {
						snippet = Snippets.around(chunk.content(), List.of(row.simpleName().toLowerCase(Locale.ROOT)));
						redacted = chunk.redacted();
					}
				}
				results.add(new SearchResultDTO("SYMBOL", null, row.id(), row.symbolType(), row.simpleName(),
						row.qualifiedName(), row.signature(), row.filePath(), row.startLine(), row.endLine(), "Java",
						row.modulePath(), null, null, null, snippet,
						scope.url(row.filePath(), row.startLine(), row.endLine()), redacted));
			}
			return new SearchResponseDTO(query, "SYMBOL", "OK", null, scope.commitSha(), "SYMBOL_GRAPH", results,
					List.of(query), index);
		}
		if (indexed) {
			String name = query.contains(".") || query.contains("#") ? query.substring(Math.max(query.lastIndexOf('.'),
					query.lastIndexOf('#')) + 1) : query;
			List<SearchResultDTO> results = chunks.byName(scope.snapshotId(), scope.repositoryId(), name, filter, max)
				.stream()
				.map(chunk -> chunkResult(scope, chunk, null, Snippets.around(chunk.content(), List.of(name.toLowerCase(Locale.ROOT)))))
				.toList();
			return new SearchResponseDTO(query, "SYMBOL", "OK",
					"No symbol graph for this snapshot (only Java/Spring code is parsed into one); matched type and method "
							+ "names of indexed chunks instead.",
					scope.commitSha(), "CHUNK_NAMES", results, List.of(query), index);
		}
		return new SearchResponseDTO(query, "SYMBOL", "NOT_SUPPORTED",
				"No symbol graph for this snapshot (only Java/Spring code is parsed into one) and the snapshot is not indexed.",
				scope.commitSha(), null, List.of(), List.of(query), index);
	}

	/** The stored chunk of a symbol: the class chunk of a type, the method chunk of a member. */
	StoredChunk chunkOf(RagScope scope, SymbolLookup.SymbolRow row) {
		for (StoredChunk chunk : chunks.overlapping(scope.snapshotId(), scope.repositoryId(), row.filePath(),
				row.startLine(), row.endLine())) {
			if (!row.typeName().equals(chunk.className())) {
				continue;
			}
			if (row.isType() ? chunk.chunkType() == ChunkType.CLASS
					: chunk.chunkType() == ChunkType.METHOD && row.simpleName().equals(chunk.methodName())) {
				return chunk;
			}
		}
		return null;
	}

	static SearchResultDTO chunkResult(RagScope scope, StoredChunk chunk, Double score, String snippet) {
		return new SearchResultDTO("CHUNK", chunk.id(), null, null, chunk.label(), chunk.className(), null, chunk.filePath(),
				chunk.startLine(), chunk.endLine(), chunk.language(), chunk.modulePath(), chunk.chunkType().name(),
				chunk.section(), score, snippet, scope.url(chunk.filePath(), chunk.startLine(), chunk.endLine()),
				chunk.redacted());
	}

	private static Double round(double value) {
		return Math.round(value * 10000) / 10000.0;
	}

}
