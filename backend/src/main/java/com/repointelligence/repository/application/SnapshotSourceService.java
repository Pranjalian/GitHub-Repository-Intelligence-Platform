package com.repointelligence.repository.application;

import com.repointelligence.common.exception.UpstreamRateLimitException;
import com.repointelligence.repository.infrastructure.storage.WorkspaceManager;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads source lines of an analysed snapshot on demand (AI insights), bounded to a line range. Uses the
 * retained workspace when it exists; otherwise, when allowed, the workspace is rebuilt once from the
 * immutable tarball of the snapshot's commit (one GitHub download) and is removed by the regular sweep.
 * Repository code is only read as text, never executed.
 */
@Service
public class SnapshotSourceService {

	private static final Logger log = LoggerFactory.getLogger(SnapshotSourceService.class);

	/** Largest file read for an excerpt. */
	static final long MAX_FILE_BYTES = 512 * 1024L;

	private final WorkspaceManager workspaces;

	private final IngestionService ingestion;

	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

	public SnapshotSourceService(WorkspaceManager workspaces, IngestionService ingestion) {
		this.workspaces = workspaces;
		this.ingestion = ingestion;
	}

	/** Lines {@code from..to} (1-based, inclusive, clamped to the file) or the reason they are unavailable. */
	public record Excerpt(String text, Integer startLine, Integer endLine, int fileLines, String unavailableReason) {

		public boolean available() {
			return unavailableReason == null;
		}

		static Excerpt unavailable(String reason) {
			return new Excerpt(null, null, null, 0, reason);
		}

	}

	/** Makes sure the workspace exists; returns null when ready, else the reason it is not. */
	public String ensureWorkspace(UUID snapshotId, boolean allowDownload) {
		if (workspaces.exists(snapshotId)) {
			return null;
		}
		if (!allowDownload) {
			return "the extracted source is no longer kept and re-downloading is disabled (ai.source.allow-download=false)";
		}
		synchronized (locks.computeIfAbsent(snapshotId, id -> new Object())) {
			if (workspaces.exists(snapshotId)) {
				return null;
			}
			try {
				ingestion.restoreWorkspace(snapshotId);
				return null;
			}
			catch (UpstreamRateLimitException ex) {
				return "the source could not be downloaded again: GitHub rate limit reached";
			}
			catch (IOException | RuntimeException ex) {
				log.warn("Could not restore workspace of snapshot {}: {}", snapshotId, ex.getClass().getSimpleName());
				return "the source could not be downloaded again (" + ex.getClass().getSimpleName() + ")";
			}
		}
	}

	public Excerpt read(UUID snapshotId, String path, int from, int to, boolean allowDownload) {
		if (path == null || path.isBlank()) {
			return Excerpt.unavailable("no file path");
		}
		String problem = ensureWorkspace(snapshotId, allowDownload);
		if (problem != null) {
			return Excerpt.unavailable(problem);
		}
		String content;
		try {
			if (!workspaces.view(snapshotId).exists(path)) {
				return Excerpt.unavailable(path + " is not in the extracted source (binary, too large or excluded)");
			}
			content = workspaces.view(snapshotId).readString(path, MAX_FILE_BYTES);
		}
		catch (IOException | RuntimeException ex) {
			return Excerpt.unavailable(path + " could not be read (" + ex.getClass().getSimpleName() + ")");
		}
		String[] lines = content.split("\r?\n", -1);
		int start = Math.max(1, from);
		int end = Math.min(lines.length, Math.max(start, to));
		if (start > lines.length) {
			return Excerpt.unavailable(path + " has fewer than " + start + " lines");
		}
		StringBuilder text = new StringBuilder();
		for (int i = start; i <= end; i++) {
			if (i > start) {
				text.append('\n');
			}
			text.append(lines[i - 1]);
		}
		return new Excerpt(text.toString(), start, end, lines.length, null);
	}

}
