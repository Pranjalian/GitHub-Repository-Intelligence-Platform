package com.repointelligence.analysis.packs.java.architecture;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.packs.java.maven.MavenModule;
import com.repointelligence.analysis.packs.java.maven.MavenModule.ResolvedDependency;
import com.repointelligence.analysis.packs.java.maven.MavenReactor;
import com.repointelligence.analysis.spi.ArchitectureResult.ModuleDependency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Maven modules of a snapshot (from the MVP 1 reactor model): which module a source file belongs to,
 * and which modules depend on which. A module depends on another when it declares a dependency on that
 * module's {@code groupId:artifactId}; nothing is resolved from outside the snapshot.
 */
public final class ModuleLayout {

	/** Used when the snapshot has no {@code pom.xml}. */
	public static final String ROOT = ".";

	private final List<MavenModule> modules;

	private final List<MavenModule> byDepth;

	private ModuleLayout(List<MavenModule> modules) {
		this.modules = List.copyOf(modules);
		this.byDepth = modules.stream().sorted(Comparator.comparingInt((MavenModule m) -> m.directory().length()).reversed()).toList();
	}

	public static ModuleLayout of(MavenReactor reactor) {
		return new ModuleLayout(reactor == null ? List.of() : reactor.modules());
	}

	/** The deepest module directory containing the file, {@code .} for the root or when there are no modules. */
	public String modulePathOf(String filePath) {
		for (MavenModule module : byDepth) {
			String directory = module.directory();
			if (directory.isEmpty() || filePath.startsWith(directory + "/")) {
				return module.modulePath();
			}
		}
		return ROOT;
	}

	public List<ModuleDependency> dependencies() {
		Map<String, MavenModule> byCoordinate = new HashMap<>();
		for (MavenModule module : modules) {
			if (module.effectiveGroupId() != null && module.model().getArtifactId() != null) {
				byCoordinate.put(module.effectiveGroupId() + ":" + module.model().getArtifactId(), module);
			}
		}
		Map<String, List<Evidence>> edges = new LinkedHashMap<>();
		for (MavenModule module : modules) {
			for (ResolvedDependency dependency : module.dependencies()) {
				MavenModule target = byCoordinate.get(dependency.coordinate());
				if (target == null || target == module) {
					continue;
				}
				edges.computeIfAbsent(module.modulePath() + "\n" + target.modulePath(), k -> new ArrayList<>())
					.add(new Evidence(module.pomPath(), dependency.line(),
							"depends on " + dependency.coordinate() + " (" + dependency.scope() + ")"));
			}
		}
		List<ModuleDependency> result = new ArrayList<>();
		edges.forEach((key, evidence) -> {
			String[] parts = key.split("\n", 2);
			result.add(new ModuleDependency(parts[0], parts[1], evidence));
		});
		return result;
	}

	public int moduleCount() {
		return modules.size();
	}

}
