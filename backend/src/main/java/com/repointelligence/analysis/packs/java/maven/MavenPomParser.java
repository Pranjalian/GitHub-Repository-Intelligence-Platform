package com.repointelligence.analysis.packs.java.maven;

import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3ReaderEx;

/**
 * Reads every {@code pom.xml} of a snapshot into a {@link MavenReactor}. It uses {@code maven-model}'s
 * plain XML reader only: nothing is downloaded, no parent or BOM is fetched, and no external entity or
 * DTD is resolved, so a hostile POM cannot read local files or reach the network. A POM that cannot be
 * read becomes a warning for that module; the others are still parsed.
 */
public final class MavenPomParser {

	/** Larger POMs are not parsed (they are not real build files). */
	private static final int MAX_POM_BYTES = 1024 * 1024;

	private MavenPomParser() {
	}

	/** Paths of the {@code pom.xml} files among the extracted files, sorted. */
	public static List<String> pomPaths(List<FileInfo> files) {
		return files.stream()
			.filter(FileInfo::extracted)
			.map(FileInfo::path)
			.filter(path -> "pom.xml".equals(baseName(path)))
			.sorted()
			.toList();
	}

	public static MavenReactor parse(WorkspaceView workspace, List<FileInfo> files) {
		Map<String, Model> models = new LinkedHashMap<>();
		List<String> warnings = new ArrayList<>();
		for (String pomPath : pomPaths(files)) {
			try (InputStream in = workspace.open(pomPath)) {
				byte[] bytes = in.readNBytes(MAX_POM_BYTES + 1);
				if (bytes.length > MAX_POM_BYTES) {
					warnings.add(pomPath + ": skipped, the file is larger than 1 MB.");
					continue;
				}
				models.put(pomPath, new MavenXpp3ReaderEx().read(new java.io.ByteArrayInputStream(bytes), false, new InputSource()));
			}
			catch (Exception ex) {
				warnings.add(pomPath + ": could not be parsed (" + ex.getClass().getSimpleName() + "); its dependencies are missing from the inventory.");
			}
		}
		return link(models, warnings);
	}

	/** Test seam: build a reactor from already-read models. */
	static MavenReactor link(Map<String, Model> models, List<String> warnings) {
		Map<String, MavenModule> byPath = new LinkedHashMap<>();
		for (Map.Entry<String, Model> entry : models.entrySet()) {
			byPath.put(entry.getKey(), new MavenModule(entry.getKey(), entry.getValue()));
		}
		// Local parent resolution by relativePath (default ../pom.xml), accepted only when the coordinates match.
		for (MavenModule module : byPath.values()) {
			var parent = module.model().getParent();
			if (parent == null) {
				continue;
			}
			String relativePath = parent.getRelativePath();
			if (relativePath != null && relativePath.isBlank()) {
				continue; // an explicit empty relativePath means "look it up remotely"
			}
			String target = resolveRelative(module.directory(), relativePath == null ? "../pom.xml" : relativePath);
			MavenModule candidate = byPath.get(target);
			if (candidate == null && relativePath != null) {
				candidate = byPath.get(resolveRelative(module.directory(), relativePath + "/pom.xml"));
			}
			if (candidate != null && candidate != module && sameCoordinates(candidate, parent.getGroupId(),
					parent.getArtifactId())) {
				module.linkParent(candidate);
			}
		}
		// Children listed in <modules> but with an unrelated parent are still part of the reactor.
		Map<String, String> declaredBy = new HashMap<>();
		for (MavenModule module : byPath.values()) {
			for (String child : module.model().getModules()) {
				String childPom = resolveRelative(module.directory(), child.endsWith(".xml") ? child : child + "/pom.xml");
				declaredBy.put(childPom, module.pomPath());
			}
		}
		byPath.values().forEach(module -> module.setDeclaredBy(declaredBy.get(module.pomPath())));
		return new MavenReactor(List.copyOf(byPath.values()), List.copyOf(warnings));
	}

	private static boolean sameCoordinates(MavenModule candidate, String groupId, String artifactId) {
		Model model = candidate.model();
		String candidateGroup = model.getGroupId() != null ? model.getGroupId()
				: model.getParent() != null ? model.getParent().getGroupId() : null;
		return artifactId != null && artifactId.equals(model.getArtifactId())
				&& (groupId == null || candidateGroup == null || groupId.equals(candidateGroup));
	}

	/** Resolves {@code relative} against {@code directory} (workspace-relative, forward slashes). */
	static String resolveRelative(String directory, String relative) {
		String joined = directory.isEmpty() ? relative : directory + "/" + relative;
		Path normalised = Path.of(joined.replace('\\', '/')).normalize();
		return normalised.toString().replace('\\', '/');
	}

	static String baseName(String path) {
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	/** Names of Maven scopes that are test-only (informational). */
	static final Set<String> TEST_SCOPES = Set.of("test");

}
