package com.repointelligence.analysis.packs.java.maven;

import com.repointelligence.analysis.domain.VersionSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * One {@code pom.xml} of the reactor with everything that can be worked out from files in the
 * snapshot: inherited local properties, local dependency management, and the dependencies with their
 * versions resolved and honestly labelled. Remote parents and imported BOMs are never fetched, so
 * versions they would supply are reported as {@code MANAGED_REMOTE_UNRESOLVED}.
 */
public final class MavenModule {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

	private static final int MAX_INTERPOLATION_DEPTH = 10;

	/** A resolved dependency or management entry. {@code resolvedVersion} is null when it could not be determined. */
	public record ResolvedDependency(String groupId, String artifactId, String type, String classifier, String scope,
			String declaredVersion, String resolvedVersion, VersionSource versionSource, String note,
			boolean managementEntry, boolean bomImport, boolean optional, String profileId, String pomPath,
			Integer line) {

		public String coordinate() {
			return groupId + ":" + artifactId;
		}

	}

	/** The result of replacing {@code ${...}} placeholders; unresolved ones stay literal. */
	public record Interpolation(String value, boolean fullyResolved) {
	}

	private final String pomPath;

	private final Model model;

	private MavenModule localParent;

	private String declaredBy;

	MavenModule(String pomPath, Model model) {
		this.pomPath = pomPath;
		this.model = model;
	}

	void linkParent(MavenModule parent) {
		this.localParent = parent;
	}

	void setDeclaredBy(String declaredBy) {
		this.declaredBy = declaredBy;
	}

	public String pomPath() {
		return pomPath;
	}

	public Model model() {
		return model;
	}

	public MavenModule localParent() {
		return localParent;
	}

	/** The {@code pom.xml} that lists this module in {@code <modules>}, if any. */
	public String declaredBy() {
		return declaredBy;
	}

	/** Directory of the POM, workspace-relative; empty for the root. */
	public String directory() {
		int slash = pomPath.lastIndexOf('/');
		return slash >= 0 ? pomPath.substring(0, slash) : "";
	}

	/** Path used to identify the module in results; {@code .} for the root. */
	public String modulePath() {
		return directory().isEmpty() ? "." : directory();
	}

	public String name() {
		return model.getArtifactId() != null ? model.getArtifactId() : modulePath();
	}

	public String effectiveVersion() {
		if (model.getVersion() != null) {
			return model.getVersion();
		}
		return model.getParent() != null ? model.getParent().getVersion() : null;
	}

	public String effectiveGroupId() {
		if (model.getGroupId() != null) {
			return model.getGroupId();
		}
		return model.getParent() != null ? model.getParent().getGroupId() : null;
	}

	/** The parent that is not part of the snapshot, found by walking up the local parent chain. */
	public Optional<Parent> remoteParent() {
		MavenModule top = this;
		while (top.localParent != null) {
			top = top.localParent;
		}
		return Optional.ofNullable(top.model.getParent());
	}

	// ── properties and interpolation ──────────────────────────────────────────────────────────────

	/** Own properties over inherited local ones: a child overrides its parent. */
	public Map<String, String> properties() {
		Map<String, String> properties = new LinkedHashMap<>();
		if (localParent != null) {
			properties.putAll(localParent.properties());
		}
		model.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
		return properties;
	}

	public Interpolation interpolate(String raw) {
		if (raw == null) {
			return new Interpolation(null, true);
		}
		Map<String, String> properties = properties();
		String current = raw;
		for (int depth = 0; depth < MAX_INTERPOLATION_DEPTH; depth++) {
			Matcher matcher = PLACEHOLDER.matcher(current);
			StringBuilder out = new StringBuilder();
			boolean changed = false;
			while (matcher.find()) {
				String value = lookup(matcher.group(1), properties);
				if (value == null) {
					matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
				}
				else {
					matcher.appendReplacement(out, Matcher.quoteReplacement(value));
					changed = true;
				}
			}
			matcher.appendTail(out);
			current = out.toString();
			if (!changed) {
				break;
			}
		}
		return new Interpolation(current, !PLACEHOLDER.matcher(current).find());
	}

	private String lookup(String key, Map<String, String> properties) {
		Parent parent = model.getParent();
		return switch (key) {
			case "project.version", "pom.version", "version" -> effectiveVersion();
			case "project.groupId", "pom.groupId", "groupId" -> effectiveGroupId();
			case "project.artifactId", "pom.artifactId", "artifactId" -> model.getArtifactId();
			case "project.parent.version", "parent.version" -> parent != null ? parent.getVersion() : null;
			case "project.parent.groupId", "parent.groupId" -> parent != null ? parent.getGroupId() : null;
			case "project.parent.artifactId", "parent.artifactId" -> parent != null ? parent.getArtifactId() : null;
			default -> properties.get(key);
		};
	}

	// ── dependency management ─────────────────────────────────────────────────────────────────────

	/** Effective local {@code dependencyManagement}: parent entries first, so the child's win. Keyed by g:a:type:classifier. */
	public Map<String, Dependency> localManagement() {
		Map<String, Dependency> management = new LinkedHashMap<>();
		if (localParent != null) {
			management.putAll(localParent.localManagement());
		}
		if (model.getDependencyManagement() != null) {
			for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
				management.put(key(dependency), dependency);
			}
		}
		return management;
	}

	/** BOMs imported by this module and its local parents: recorded, never fetched. */
	public List<Dependency> bomImports() {
		List<Dependency> imports = new ArrayList<>();
		if (localParent != null) {
			imports.addAll(localParent.bomImports());
		}
		if (model.getDependencyManagement() != null) {
			for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
				if (isBom(dependency)) {
					imports.add(dependency);
				}
			}
		}
		return imports;
	}

	private static boolean isBom(Dependency dependency) {
		return "import".equals(dependency.getScope()) && "pom".equals(dependency.getType());
	}

	static String key(Dependency dependency) {
		return dependency.getGroupId() + ":" + dependency.getArtifactId() + ":"
				+ (dependency.getType() == null ? "jar" : dependency.getType()) + ":"
				+ (dependency.getClassifier() == null ? "" : dependency.getClassifier());
	}

	/** The entries of this POM's own {@code <dependencyManagement>} (including BOM imports), resolved. */
	public List<ResolvedDependency> managementEntries() {
		List<ResolvedDependency> entries = new ArrayList<>();
		if (model.getDependencyManagement() == null) {
			return entries;
		}
		for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
			boolean bom = isBom(dependency);
			Interpolation version = interpolate(dependency.getVersion());
			VersionSource source = sourceOf(dependency.getVersion(), version);
			String note = bom ? "BOM import recorded, not fetched: its managed versions are not resolved." : null;
			entries.add(new ResolvedDependency(dependency.getGroupId(), dependency.getArtifactId(),
					typeOf(dependency), dependency.getClassifier(), dependency.getScope(), dependency.getVersion(),
					version.fullyResolved() ? version.value() : null, source, note, true, bom, dependency.isOptional(),
					null, pomPath, lineOf(dependency)));
		}
		return entries;
	}

	// ── dependencies ──────────────────────────────────────────────────────────────────────────────

	/** Direct dependencies from {@code <dependencies>} and from {@code <profiles>}, with versions resolved. */
	public List<ResolvedDependency> dependencies() {
		List<ResolvedDependency> result = new ArrayList<>();
		Map<String, Dependency> management = localManagement();
		for (Dependency dependency : model.getDependencies()) {
			result.add(resolve(dependency, management, null));
		}
		for (Profile profile : model.getProfiles()) {
			for (Dependency dependency : profile.getDependencies()) {
				result.add(resolve(dependency, management, profile.getId()));
			}
		}
		return result;
	}

	private ResolvedDependency resolve(Dependency dependency, Map<String, Dependency> management, String profileId) {
		String raw = dependency.getVersion();
		String scope = dependency.getScope();
		String resolvedVersion = null;
		VersionSource source;
		String note = null;
		if (raw != null) {
			Interpolation version = interpolate(raw);
			source = sourceOf(raw, version);
			resolvedVersion = version.fullyResolved() ? version.value() : null;
			if (source == VersionSource.UNRESOLVED) {
				note = "The version placeholder " + raw + " could not be resolved from the POMs in this repository.";
			}
		}
		else {
			Dependency managed = management.get(key(dependency));
			if (managed != null) {
				Interpolation version = interpolate(managed.getVersion());
				if (version.fullyResolved() && version.value() != null) {
					source = VersionSource.MANAGED_LOCAL;
					resolvedVersion = version.value();
				}
				else {
					source = VersionSource.UNRESOLVED;
					note = "The managed version " + managed.getVersion() + " could not be resolved.";
				}
				if (scope == null) {
					scope = managed.getScope();
				}
			}
			else {
				source = VersionSource.MANAGED_REMOTE_UNRESOLVED;
				note = remoteManagementNote();
				if (note == null) {
					source = VersionSource.UNRESOLVED;
					note = "No version is declared and no local parent or BOM manages it.";
				}
			}
		}
		if (profileId != null) {
			String profileNote = "declared in profile " + profileId;
			note = note == null ? profileNote : note + " (" + profileNote + ")";
		}
		return new ResolvedDependency(dependency.getGroupId(), dependency.getArtifactId(), typeOf(dependency),
				dependency.getClassifier(), scope == null ? "compile" : scope, raw, resolvedVersion, source, note,
				false, false, dependency.isOptional(), profileId, pomPath, lineOf(dependency));
	}

	/** "managed by parent g:a:v (not resolved)" when a remote parent or BOM could supply the version. */
	private String remoteManagementNote() {
		Optional<Parent> parent = remoteParent();
		if (parent.isPresent()) {
			Parent p = parent.get();
			return "managed by parent " + p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion() + " (not resolved)";
		}
		List<Dependency> boms = bomImports();
		if (!boms.isEmpty()) {
			Dependency bom = boms.get(0);
			return "managed by imported BOM " + bom.getGroupId() + ":" + bom.getArtifactId() + ":" + bom.getVersion()
					+ " (not resolved)";
		}
		return null;
	}

	private static VersionSource sourceOf(String raw, Interpolation interpolated) {
		if (raw == null) {
			return VersionSource.UNRESOLVED;
		}
		if (!interpolated.fullyResolved()) {
			return VersionSource.UNRESOLVED;
		}
		return raw.contains("${") ? VersionSource.PROPERTY : VersionSource.DECLARED;
	}

	private static Integer lineOf(Dependency dependency) {
		InputLocation location = dependency.getLocation("artifactId");
		return location == null ? null : location.getLineNumber();
	}

	private static String typeOf(Dependency dependency) {
		return dependency.getType() == null ? "jar" : dependency.getType();
	}

	// ── plugins and Java version ──────────────────────────────────────────────────────────────────

	private List<Plugin> plugins() {
		List<Plugin> plugins = new ArrayList<>();
		if (localParent != null) {
			plugins.addAll(localParent.plugins());
		}
		if (model.getBuild() != null) {
			plugins.addAll(model.getBuild().getPlugins());
			if (model.getBuild().getPluginManagement() != null) {
				plugins.addAll(model.getBuild().getPluginManagement().getPlugins());
			}
		}
		return plugins;
	}

	public boolean hasPlugin(String groupId, String artifactId) {
		return plugins().stream().anyMatch(p -> artifactId.equals(p.getArtifactId())
				&& (p.getGroupId() == null || groupId.equals(p.getGroupId())));
	}

	/** The version of a plugin if the POMs declare it locally. */
	public Optional<String> pluginVersion(String groupId, String artifactId) {
		return plugins().stream()
			.filter(p -> artifactId.equals(p.getArtifactId()) && (p.getGroupId() == null || groupId.equals(p.getGroupId())))
			.map(Plugin::getVersion)
			.filter(version -> version != null)
			.map(version -> interpolate(version))
			.filter(Interpolation::fullyResolved)
			.map(Interpolation::value)
			.findFirst();
	}

	private Optional<String> pluginConfiguration(String groupId, String artifactId, String child) {
		for (Plugin plugin : plugins()) {
			if (!artifactId.equals(plugin.getArtifactId()) || !(plugin.getConfiguration() instanceof Xpp3Dom dom)) {
				continue;
			}
			Xpp3Dom node = dom.getChild(child);
			if (node != null && node.getValue() != null) {
				Interpolation value = interpolate(node.getValue().trim());
				if (value.fullyResolved()) {
					return Optional.of(value.value());
				}
			}
		}
		return Optional.empty();
	}

	/** The Java version this module declares (release, java.version, source or target), if any. */
	public Optional<String> javaVersion() {
		Map<String, String> properties = properties();
		for (String key : List.of("maven.compiler.release", "java.version", "maven.compiler.source",
				"maven.compiler.target")) {
			String value = properties.get(key);
			if (value != null) {
				Interpolation interpolated = interpolate(value);
				if (interpolated.fullyResolved()) {
					return Optional.of(interpolated.value());
				}
			}
		}
		for (String child : List.of("release", "source", "target")) {
			Optional<String> value = pluginConfiguration("org.apache.maven.plugins", "maven-compiler-plugin", child);
			if (value.isPresent()) {
				return value;
			}
		}
		return Optional.empty();
	}

}
