package com.repointelligence.support;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A workspace held in memory for analyzer unit tests: path to text content. */
public class InMemoryWorkspace implements WorkspaceView {

	private final Map<String, String> files = new LinkedHashMap<>();

	public InMemoryWorkspace add(String path, String content) {
		files.put(path, content);
		return this;
	}

	public Map<String, String> files() {
		return files;
	}

	/** Every file of {@code src/test/resources/fixtures/<name>}, with {@code _target/} stored as {@code target/}. */
	public static InMemoryWorkspace fromFixture(String name) {
		try {
			Path root = Path.of(InMemoryWorkspace.class.getResource("/fixtures/" + name).toURI());
			InMemoryWorkspace workspace = new InMemoryWorkspace();
			try (java.util.stream.Stream<Path> paths = java.nio.file.Files.walk(root)) {
				for (Path path : paths.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
					String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/').replace("_target/", "target/");
					workspace.add(relative, java.nio.file.Files.readString(path));
				}
			}
			return workspace;
		}
		catch (java.net.URISyntaxException | IOException ex) {
			throw new IllegalStateException("Cannot load fixture " + name, ex);
		}
	}

	@Override
	public List<String> list(String glob) {
		return new ArrayList<>(files.keySet());
	}

	@Override
	public boolean exists(String path) {
		return files.containsKey(path);
	}

	@Override
	public String readString(String path, long maxBytes) throws IOException {
		String content = files.get(path);
		if (content == null) {
			throw new NoSuchFileException(path);
		}
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		return bytes.length <= maxBytes ? content : new String(bytes, 0, (int) maxBytes, StandardCharsets.UTF_8);
	}

	@Override
	public InputStream open(String path) throws IOException {
		String content = files.get(path);
		if (content == null) {
			throw new NoSuchFileException(path);
		}
		return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public Path resolveForTool() {
		throw new UnsupportedOperationException("in-memory workspace");
	}

	/** File catalogue entries for every file, classified loosely by path. */
	public List<FileInfo> fileInfos() {
		List<FileInfo> infos = new ArrayList<>();
		files.forEach((path, content) -> infos.add(new FileInfo(path, languageOf(path), typeOf(path),
				content.getBytes(StandardCharsets.UTF_8).length, "hash-" + path.hashCode(), false, null)));
		return infos;
	}

	public AnalysisContext context() {
		return new AnalysisContext(null, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40), this, fileInfos(),
				List.of(), AnalyzerSettings.defaults(), null, null);
	}

	private static String languageOf(String path) {
		if (path.endsWith(".java")) {
			return "Java";
		}
		if (path.endsWith(".ts") || path.endsWith(".tsx")) {
			return "TypeScript";
		}
		if (path.endsWith(".js") || path.endsWith(".jsx")) {
			return "JavaScript";
		}
		return null;
	}

	private static FileType typeOf(String path) {
		if (path.contains(".test.") || path.contains("_test.") || path.startsWith("tests/") || path.contains("/tests/")
				|| path.contains("/test/") ||path.matches(".*(Test|Tests|IT)\\.java")) {
			return FileType.TEST;
		}
		if (path.endsWith("pom.xml") || path.endsWith("package.json")) {
			return FileType.BUILD;
		}
		if (path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties") || path.endsWith(".json")) {
			return FileType.CONFIG;
		}
		return FileType.SOURCE;
	}

}
