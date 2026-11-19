package com.repointelligence.repository.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.util.AntPathMatcher;

/** {@link WorkspaceView} over a directory. Never follows symbolic links. */
public class PathWorkspaceView implements WorkspaceView {

	private final Path root;

	private final AntPathMatcher matcher = new AntPathMatcher("/");

	public PathWorkspaceView(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	@Override
	public List<String> list(String glob) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.map(path -> root.relativize(path).toString().replace('\\', '/'))
				.filter(relative -> matcher.match(glob, relative))
				.sorted()
				.toList();
		}
	}

	@Override
	public boolean exists(String path) {
		try {
			return Files.isRegularFile(resolve(path), LinkOption.NOFOLLOW_LINKS);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	@Override
	public String readString(String path, long maxBytes) throws IOException {
		try (InputStream in = open(path)) {
			return new String(in.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE)), StandardCharsets.UTF_8);
		}
	}

	@Override
	public InputStream open(String path) throws IOException {
		Path file = resolve(path);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new NoSuchFileException(path);
		}
		return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
	}

	@Override
	public Path resolveForTool() {
		return root;
	}

	/** Resolves a relative path, refusing absolute paths, drive letters and traversal. */
	private Path resolve(String path) {
		if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
				|| path.indexOf(':') >= 0) {
			throw new IllegalArgumentException("Path must be relative to the workspace: " + path);
		}
		Path resolved = root.resolve(path).normalize();
		if (!resolved.startsWith(root) || resolved.equals(root)) {
			throw new IllegalArgumentException("Path escapes the workspace: " + path);
		}
		return resolved;
	}

}
