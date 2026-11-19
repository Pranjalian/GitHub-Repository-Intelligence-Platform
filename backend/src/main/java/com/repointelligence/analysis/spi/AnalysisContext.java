package com.repointelligence.analysis.spi;

import com.repointelligence.common.validation.GitHubRepositoryRef;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything an analyzer may read. It is read-only: analyzers get the workspace through a path-safe
 * view and plain DTOs, never repositories or the EntityManager. {@code technologies} is empty while
 * detectors run and holds the merged detections afterwards.
 */
public record AnalysisContext(GitHubRepositoryRef repositoryRef, UUID snapshotId, UUID runId, String commitSha,
		WorkspaceView workspace, List<FileInfo> files, List<DetectedTechnology> technologies,
		AnalyzerSettings settings, Instant deadline, CancellationToken cancellation) {

	public AnalysisContext {
		Objects.requireNonNull(workspace, "workspace");
		files = files == null ? List.of() : List.copyOf(files);
		technologies = technologies == null ? List.of() : List.copyOf(technologies);
		settings = settings == null ? AnalyzerSettings.defaults() : settings;
		cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
	}

	/** The context handed to analyzers once detection is done. */
	public AnalysisContext withTechnologies(List<DetectedTechnology> detected) {
		return new AnalysisContext(repositoryRef, snapshotId, runId, commitSha, workspace, files, detected, settings,
				deadline, cancellation);
	}

	/** The same context with another cancellation token (the orchestrator adds a per-analyzer one). */
	public AnalysisContext withCancellation(CancellationToken token) {
		return new AnalysisContext(repositoryRef, snapshotId, runId, commitSha, workspace, files, technologies, settings,
				deadline, token);
	}

	/** Files whose content is in the workspace (not binary, not skipped). */
	public List<FileInfo> extractedFiles() {
		return files.stream().filter(FileInfo::extracted).toList();
	}

	public Optional<DetectedTechnology> technology(String name) {
		return technologies.stream().filter(t -> t.technology().equalsIgnoreCase(name)).findFirst();
	}

	public boolean hasTechnology(String name) {
		return technology(name).isPresent();
	}

	/** Time left before the analysis deadline; effectively unbounded when no deadline is set. */
	public Duration remaining() {
		return deadline == null ? Duration.ofDays(365) : Duration.between(Instant.now(), deadline);
	}

}
