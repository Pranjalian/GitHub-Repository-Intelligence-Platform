package com.repointelligence.rag.api;

import com.repointelligence.rag.indexing.SnapshotIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Optional, per-snapshot index for search and Q&A (MVP 4); separate from the analysis pipeline. */
@Tag(name = "Search index", description = "Chunks a snapshot's files for keyword, symbol and semantic search (MVP 4).")
@RestController
public class IndexController {

	private final SnapshotIndexService service;

	public IndexController(SnapshotIndexService service) {
		this.service = service;
	}

	@Operation(summary = "Index a snapshot",
			description = "Queues an index job: files of the snapshot are secret-scrubbed and chunked (Java by class and "
					+ "method, YAML/properties by section, Markdown by heading, other files by labelled line windows) "
					+ "into a keyword index; embeddings are added when an embedding model is configured. Needs a "
					+ "completed analysis (404 otherwise); no AI is needed. Returns the current state when a job is "
					+ "already queued or running; 429 when too many jobs are waiting.")
	@PostMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/index")
	public ResponseEntity<IndexStatusDTO> index(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.request(repositoryId, snapshotId));
	}

	@Operation(summary = "Index state of a snapshot", description = "NOT_INDEXED until an index job was requested.")
	@GetMapping("/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/index")
	public IndexStatusDTO status(@PathVariable UUID repositoryId, @PathVariable UUID snapshotId) {
		return service.status(repositoryId, snapshotId);
	}

}
