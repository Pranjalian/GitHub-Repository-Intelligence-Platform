package com.repointelligence.rag.api;

import com.repointelligence.rag.retrieval.ChunkFilter;
import com.repointelligence.rag.retrieval.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Deterministic code search; works with no model configured (MVP 4). */
@Tag(name = "Search", description = "Exact symbol lookup and full-text search in one snapshot; no AI involved (MVP 4).")
@RestController
public class SearchController {

	private final SearchService service;

	public SearchController(SearchService service) {
		this.service = service;
	}

	@Operation(summary = "Search a snapshot",
			description = "type=symbol: exact identifier lookup in the symbol graph (simple or qualified name, Type.member); "
					+ "works right after the analysis. type=text: full-text search in the snapshot's index (all words must "
					+ "match; identifiers also match by their camelCase parts); needs the snapshot to be indexed "
					+ "(status NOT_INDEXED otherwise). Results link to GitHub at the snapshot's commit.")
	@GetMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/search")
	public SearchResponseDTO search(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId,
			@RequestParam("q") String query,
			@Parameter(description = "symbol or text (default)") @RequestParam(required = false) String type,
			@RequestParam(required = false) String language, @RequestParam(required = false) String module,
			@Parameter(description = "SOURCE, TEST, CONFIG, BUILD or DOCS") @RequestParam(required = false) String fileType,
			@RequestParam(required = false) Integer limit) {
		return service.search(repositoryId, snapshotId, query, type, new ChunkFilter(language, module, fileType), limit);
	}

}
