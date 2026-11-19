package com.repointelligence.repository.infrastructure.storage;

import com.repointelligence.repository.application.WorkspaceProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the on-disk workspaces: one directory per snapshot under the configured root. Source is not
 * retained indefinitely; a workspace can always be rebuilt by re-downloading the immutable tarball
 * for the snapshot's commit SHA.
 */
@Component
public class WorkspaceManager {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

	static final String DOWNLOADS_DIR = "_downloads";

	private final WorkspaceProperties properties;

	private final SafeTarExtractor extractor;

	public WorkspaceManager(WorkspaceProperties properties, SafeTarExtractor extractor) {
		this.properties = properties;
		this.extractor = extractor;
	}

	public Path root() {
		return properties.root();
	}

	public Path workspaceDir(UUID snapshotId) {
		return properties.root().resolve(snapshotId.toString());
	}

	public boolean exists(UUID snapshotId) {
		return Files.isDirectory(workspaceDir(snapshotId));
	}

	/** Where the tarball for a snapshot is downloaded before extraction. */
	public Path downloadPath(UUID snapshotId) throws IOException {
		Path dir = properties.root().resolve(DOWNLOADS_DIR);
		Files.createDirectories(dir);
		return dir.resolve(snapshotId + ".tar.gz");
	}

	/** Extracts into a fresh workspace; a failed extraction leaves nothing behind. */
	public ExtractionResult extract(Path archive, UUID snapshotId, ExtractionLimits limits) throws IOException {
		delete(snapshotId);
		Path dir = workspaceDir(snapshotId);
		try {
			return extractor.extract(archive, dir, limits);
		}
		catch (IOException | RuntimeException ex) {
			delete(snapshotId);
			throw ex;
		}
	}

	public WorkspaceView view(UUID snapshotId) {
		return new PathWorkspaceView(workspaceDir(snapshotId));
	}

	/** Called when a run finishes: removes the workspace unless retention is configured. */
	public void releaseAfterRun(UUID snapshotId) {
		if (properties.retainAfterAnalysis().isZero() || properties.retainAfterAnalysis().isNegative()) {
			delete(snapshotId);
		}
	}

	public void delete(UUID snapshotId) {
		deleteRecursively(workspaceDir(snapshotId));
	}

	/** Deletes a directory tree without following symbolic links. */
	static void deleteRecursively(Path directory) {
		if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			log.warn("Could not fully delete workspace {}: {}", directory.getFileName(), ex.getClass().getSimpleName());
			throw new UncheckedIOException(ex);
		}
	}

}
