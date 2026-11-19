package com.repointelligence.repository.infrastructure.storage;

import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.application.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes orphaned workspaces: directories left behind by a crash, or kept past their retention.
 * A workspace that belongs to a queued or running analysis is never touched. Only directories named
 * like a snapshot id (and stale download files) are considered, so unrelated files are safe.
 */
@Component
public class WorkspaceSweeper {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceSweeper.class);

	private static final Set<RunStatus> IN_FLIGHT = Set.of(RunStatus.QUEUED, RunStatus.INGESTING, RunStatus.ANALYZING);

	private static final Duration STALE_DOWNLOAD = Duration.ofHours(1);

	private final WorkspaceProperties properties;

	private final AnalysisRunRepository runs;

	private final Clock clock;

	public WorkspaceSweeper(WorkspaceProperties properties, AnalysisRunRepository runs, Clock clock) {
		this.properties = properties;
		this.runs = runs;
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void sweepOnStartup() {
		sweepOrphans();
	}

	@Scheduled(fixedDelayString = "${repository.workspace.sweep-interval:PT1H}",
			initialDelayString = "${repository.workspace.sweep-interval:PT1H}")
	public void sweepScheduled() {
		sweepOrphans();
	}

	/** @return the number of directories removed */
	public int sweepOrphans() {
		Path root = properties.root();
		if (!Files.isDirectory(root)) {
			return 0;
		}
		Set<UUID> active = runs.findSnapshotIdsByStatusIn(IN_FLIGHT);
		Instant now = clock.instant();
		int removed = 0;
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.toList()) {
				String name = child.getFileName().toString();
				if (name.equals(WorkspaceManager.DOWNLOADS_DIR)) {
					sweepDownloads(child, now);
					continue;
				}
				UUID snapshotId = parseUuid(name);
				if (snapshotId == null || !Files.isDirectory(child) || active.contains(snapshotId)) {
					continue;
				}
				if (isWithinRetention(child, now)) {
					continue;
				}
				try {
					WorkspaceManager.deleteRecursively(child);
					removed++;
				}
				catch (RuntimeException ex) {
					log.warn("Could not remove orphaned workspace {}", name);
				}
			}
		}
		catch (IOException ex) {
			log.warn("Workspace sweep failed: {}", ex.getClass().getSimpleName());
		}
		if (removed > 0) {
			log.info("Removed {} orphaned workspace(s)", removed);
		}
		return removed;
	}

	private boolean isWithinRetention(Path directory, Instant now) {
		Duration retention = properties.retainAfterAnalysis();
		if (retention.isZero() || retention.isNegative()) {
			return false;
		}
		try {
			return Files.getLastModifiedTime(directory).toInstant().plus(retention).isAfter(now);
		}
		catch (IOException ex) {
			return true;
		}
	}

	private void sweepDownloads(Path directory, Instant now) {
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.toList()) {
				if (Files.getLastModifiedTime(file).toInstant().plus(STALE_DOWNLOAD).isBefore(now)) {
					Files.deleteIfExists(file);
				}
			}
		}
		catch (IOException ex) {
			log.warn("Download sweep failed: {}", ex.getClass().getSimpleName());
		}
	}

	private static UUID parseUuid(String name) {
		try {
			return UUID.fromString(name);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
