package com.repointelligence.analysis.packs.javascript;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DependencyResult;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.support.InMemoryWorkspace;
import org.junit.jupiter.api.Test;

class NpmManifestDependencyAnalyzerTests {

	private final NpmManifestDependencyAnalyzer analyzer = new NpmManifestDependencyAnalyzer();

	private static DependencyResult find(AnalyzerResult result, String name) {
		return result.dependencies().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
	}

	@Test
	void readsEveryDependencyGroupKeepingRangesAsDeclared() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("package.json", """
				{
				  "name": "web",
				  "dependencies": { "react": "^19.0.0", "@tanstack/react-query": "~5.1.0" },
				  "devDependencies": { "vite": ">=7 <9" },
				  "peerDependencies": { "react-dom": "*" },
				  "optionalDependencies": { "fsevents": "workspace:*" }
				}
				""");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(result.dependencies()).hasSize(5);
		DependencyResult react = find(result, "react");
		assertThat(react.ecosystem()).isEqualTo(Ecosystem.NPM);
		assertThat(react.declaredVersion()).isEqualTo("^19.0.0");
		assertThat(react.resolvedVersion()).isNull();
		assertThat(react.versionSource()).isEqualTo(VersionSource.DECLARED);
		assertThat(react.scope()).isEqualTo("runtime");
		assertThat(react.modulePath()).isEqualTo(".");
		assertThat(react.vulnerabilityStatus()).isEqualTo(VulnerabilityStatus.NOT_CHECKED);
		DependencyResult query = find(result, "react-query");
		assertThat(query.namespace()).isEqualTo("@tanstack");
		assertThat(find(result, "vite").scope()).isEqualTo("dev");
		assertThat(find(result, "vite").declaredVersion()).isEqualTo(">=7 <9");
		assertThat(find(result, "react-dom").scope()).isEqualTo("peer");
		assertThat(find(result, "fsevents").scope()).isEqualTo("optional");
	}

	@Test
	void everyPackageJsonIsReadAndGetsItsOwnModulePath() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("package.json", "{\"dependencies\":{\"a\":\"1\"}}")
			.add("packages/ui/package.json", "{\"dependencies\":{\"b\":\"2\"}}");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(find(result, "a").modulePath()).isEqualTo(".");
		assertThat(find(result, "b").modulePath()).isEqualTo("packages/ui");
		assertThat(find(result, "b").manifestPath()).isEqualTo("packages/ui/package.json");
	}

	@Test
	void malformedJsonIsAWarningAndOtherManifestsStillCount() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("package.json", "{ not json")
			.add("ok/package.json", "{\"dependencies\":{\"a\":\"1\"}}");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(result.warnings()).anyMatch(w -> w.startsWith("package.json"));
		assertThat(result.dependencies()).hasSize(1);
	}

	@Test
	void applicabilityAndCapability() {
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.BASIC);
		assertThat(analyzer.applicability(new InMemoryWorkspace().add("pom.xml", "<project/>").context()).kind())
			.isEqualTo(Applicability.Kind.NOT_APPLICABLE);
		assertThat(analyzer.applicability(new InMemoryWorkspace().add("web/package.json", "{}").context()).isApplicable())
			.isTrue();
	}

}
