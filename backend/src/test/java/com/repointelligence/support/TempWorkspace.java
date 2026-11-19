package com.repointelligence.support;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.CancellationToken;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.storage.PathWorkspaceView;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A real directory of files for analyzers that need a path on disk (PMD, Semgrep, report parsers). */
public class TempWorkspace {

	private final Path root;

	private final Map<String, FileType> types = new LinkedHashMap<>();

	public TempWorkspace(Path root) {
		this.root = root;
	}

	public TempWorkspace add(String path, String content) {
		return add(path, content, null);
	}

	public TempWorkspace add(String path, String content, FileType type) {
		try {
			Path file = root.resolve(path);
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		types.put(path, type != null ? type : classify(path));
		return this;
	}

	public Path root() {
		return root;
	}

	public WorkspaceView view() {
		return new PathWorkspaceView(root);
	}

	public List<FileInfo> fileInfos() {
		List<FileInfo> infos = new ArrayList<>();
		types.forEach((path, type) -> {
			try {
				infos.add(new FileInfo(path, languageOf(path), type, Files.size(root.resolve(path)), "h-" + path.hashCode(),
						false, null));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});
		return infos;
	}

	public AnalysisContext context() {
		return context(AnalyzerSettings.defaults(), null, CancellationToken.NONE);
	}

	public AnalysisContext context(AnalyzerSettings settings, Instant deadline, CancellationToken cancellation) {
		return new AnalysisContext(null, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40), view(), fileInfos(),
				List.of(), settings, deadline, cancellation);
	}

	private static FileType classify(String path) {
		if (path.contains("/src/test/") || path.startsWith("src/test/")) {
			return FileType.TEST;
		}
		if (path.endsWith("pom.xml") || path.endsWith("package.json")) {
			return FileType.BUILD;
		}
		if (path.endsWith(".java") || path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".js")) {
			return FileType.SOURCE;
		}
		return FileType.CONFIG;
	}

	private static String languageOf(String path) {
		if (path.endsWith(".java")) {
			return "Java";
		}
		if (path.endsWith(".ts") || path.endsWith(".tsx")) {
			return "TypeScript";
		}
		if (path.endsWith(".js")) {
			return "JavaScript";
		}
		return null;
	}

}
