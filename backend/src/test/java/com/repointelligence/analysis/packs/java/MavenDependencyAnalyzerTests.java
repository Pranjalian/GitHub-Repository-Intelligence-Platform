package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.DependencyResult;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.support.InMemoryWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenDependencyAnalyzerTests {

	private final MavenDependencyAnalyzer analyzer = new MavenDependencyAnalyzer();

	private static String pom(String body) {
		return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>" + body + "</project>";
	}

	private static List<FindingResult> findings(AnalyzerResult result, String ruleId) {
		return result.findings().stream().filter(f -> ruleId.equals(f.ruleId())).toList();
	}

	@Test
	void isDeepForDirectDeclarationsAndDeclaresTransitiveLimitations() {
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.DEEP);
		assertThat(analyzer.descriptor().limitations()).anyMatch(l -> l.contains("transitive"));
		assertThat(analyzer.descriptor().limitations()).anyMatch(l -> l.contains("remote parents"));
	}

	@Test
	void notApplicableWithoutAPomXml() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("package.json", "{}").add("src/App.java", "class App {}");

		assertThat(analyzer.applicability(ws.context()).kind()).isEqualTo(Applicability.Kind.NOT_APPLICABLE);
	}

	@Test
	void applicableWithAPomXml() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("svc/pom.xml", pom(""));

		assertThat(analyzer.applicability(ws.context()).isApplicable()).isTrue();
	}

	@Test
	void inventoriesDirectDependenciesAndManagementEntries() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<dependencyManagement><dependencies>
				  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version></dependency>
				</dependencies></dependencyManagement>
				<dependencies>
				  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
				  <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
				</dependencies>
				"""));

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
		List<DependencyResult> direct = result.dependencies().stream().filter(DependencyResult::direct).toList();
		assertThat(direct).hasSize(2);
		DependencyResult slf4j = direct.stream().filter(d -> d.name().equals("slf4j-api")).findFirst().orElseThrow();
		assertThat(slf4j.namespace()).isEqualTo("org.slf4j");
		assertThat(slf4j.versionSource()).isEqualTo(VersionSource.MANAGED_LOCAL);
		assertThat(slf4j.resolvedVersion()).isEqualTo("2.0.13");
		assertThat(slf4j.modulePath()).isEqualTo(".");
		assertThat(slf4j.manifestPath()).isEqualTo("pom.xml");
		assertThat(result.dependencies()).filteredOn(DependencyResult::managementEntry).hasSize(1);
		assertThat(result.limitations()).isNotEmpty();
	}

	@Test
	void flagsDuplicateDeclarationsWithinAModule() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<dependencies>
				  <dependency><groupId>x</groupId><artifactId>lib</artifactId><version>1</version></dependency>
				  <dependency><groupId>x</groupId><artifactId>lib</artifactId><version>1</version></dependency>
				</dependencies>
				"""));

		List<FindingResult> duplicates = findings(analyzer.analyze(ws.context()), "maven-duplicate-dependency");

		assertThat(duplicates).hasSize(1);
		assertThat(duplicates.get(0).title()).contains("x:lib");
		assertThat(duplicates.get(0).filePath()).isEqualTo("pom.xml");
		assertThat(duplicates.get(0).startLine()).isNotNull();
	}

	@Test
	void flagsTheSameArtifactAtDifferentVersionsAcrossModules() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("a/pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies>
				""")).add("b/pom.xml", pom("""
				<groupId>g</groupId><artifactId>b</artifactId><version>1</version>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>2.0</version></dependency></dependencies>
				"""));

		List<FindingResult> inconsistent = findings(analyzer.analyze(ws.context()), "maven-version-inconsistency");

		assertThat(inconsistent).hasSize(1);
		assertThat(inconsistent.get(0).title()).contains("x:lib");
		assertThat(inconsistent.get(0).severity()).isEqualTo(Severity.LOW);
		assertThat(inconsistent.get(0).evidence().toString()).contains("1.0").contains("2.0");
	}

	@Test
	void sameVersionEverywhereIsNotInconsistent() {
		String module = """
				<groupId>g</groupId><artifactId>%s</artifactId><version>1</version>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies>
				""";
		InMemoryWorkspace ws = new InMemoryWorkspace().add("a/pom.xml", pom(module.formatted("a")))
			.add("b/pom.xml", pom(module.formatted("b")));

		assertThat(findings(analyzer.analyze(ws.context()), "maven-version-inconsistency")).isEmpty();
	}

	@Test
	void unresolvedVersionsAreInfoFindings() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>${nope}</version></dependency></dependencies>
				"""));

		List<FindingResult> unresolved = findings(analyzer.analyze(ws.context()), "maven-unresolved-version");

		assertThat(unresolved).hasSize(1);
		assertThat(unresolved.get(0).severity()).isEqualTo(Severity.INFO);
	}

	@Test
	void remoteManagedVersionsAreOneAggregatedInfoFindingPerModule() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("""
				<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.1</version></parent>
				<artifactId>demo</artifactId>
				<dependencies>
				  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
				  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
				</dependencies>
				"""));

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.dependencies()).allSatisfy(d -> assertThat(d.versionSource()).isEqualTo(VersionSource.MANAGED_REMOTE_UNRESOLVED));
		assertThat(result.dependencies().get(0).note()).contains("(not resolved)");
		List<FindingResult> remote = findings(result, "maven-remote-managed-versions");
		assertThat(remote).hasSize(1);
		assertThat(remote.get(0).title()).startsWith("2 dependency version(s)");
	}

	@Test
	void malformedPomIsAWarningAndOtherModulesAreStillInventoried() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("ok/pom.xml", pom("""
				<groupId>g</groupId><artifactId>ok</artifactId><version>1</version>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>
				""")).add("bad/pom.xml", "<project><oops>");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(result.warnings()).anyMatch(w -> w.contains("bad/pom.xml"));
		assertThat(result.dependencies()).hasSize(1);
	}

}
