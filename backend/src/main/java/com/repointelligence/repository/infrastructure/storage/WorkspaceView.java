package com.repointelligence.repository.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only, path-safe access to one extracted snapshot, handed to analyzers. Paths are always
 * relative to the snapshot root with {@code /} separators; anything that would leave the root
 * (absolute paths, {@code ..}) is refused. Only extracted text files are visible: binary and skipped
 * entries are not in the workspace.
 */
public interface WorkspaceView {

	/** Relative paths of files matching an Ant-style glob (e.g. {@code **}{@code /pom.xml}), sorted. */
	List<String> list(String glob) throws IOException;

	boolean exists(String path);

	/** Reads at most {@code maxBytes} bytes of a file as UTF-8 (malformed input is replaced). */
	String readString(String path, long maxBytes) throws IOException;

	InputStream open(String path) throws IOException;

	/** The snapshot directory, for external tools that need a real path (e.g. Semgrep). */
	Path resolveForTool();

}
