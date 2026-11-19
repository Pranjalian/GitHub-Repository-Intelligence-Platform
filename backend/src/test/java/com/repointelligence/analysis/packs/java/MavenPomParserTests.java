package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.VersionSource;
import com.repointelligence.analysis.packs.java.maven.MavenModule;
import com.repointelligence.analysis.packs.java.maven.MavenModule.ResolvedDependency;
import com.repointelligence.analysis.packs.java.maven.MavenPomParser;
import com.repointelligence.analysis.packs.java.maven.MavenReactor;
import com.repointelligence.support.InMemoryWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenPomParserTests {

	private static String pom(String body) {
		return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n<modelVersion>4.0.0</modelVersion>\n" + body
				+ "\n</project>";
	}

	private static MavenReactor parse(InMemoryWorkspace workspace) {
		return MavenPomParser.parse(workspace, workspace.fileInfos());
	}

	private static MavenModule module(MavenReactor reactor, String pomPath) {
		return reactor.modules().stream().filter(m -> m.pomPath().equals(pomPath)).findFirst().orElseThrow();
	}

	private static ResolvedDependency dependency(MavenModule module, String artifactId) {
		return module.dependencies().stream().filter(d -> d.artifactId().equals(artifactId)).findFirst().orElseThrow();
	}

	@Test
	void simplePomListsDirectDependenciesWithScopeAndDeclaredVersion() {
		MavenReactor reactor = parse(new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version>
				<dependencies>
				  <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.14.0</version></dependency>
				  <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
				</dependencies>
				""")));

		MavenModule root = module(reactor, "pom.xml");
		assertThat(root.modulePath()).isEqualTo(".");
		ResolvedDependency lang = dependency(root, "commons-lang3");
		assertThat(lang.declaredVersion()).isEqualTo("3.14.0");
		assertThat(lang.resolvedVersion()).isEqualTo("3.14.0");
		assertThat(lang.versionSource()).isEqualTo(VersionSource.DECLARED);
		assertThat(lang.scope()).isEqualTo("compile");
		assertThat(lang.line()).isNotNull();
		assertThat(dependency(root, "junit").scope()).isEqualTo("test");
		assertThat(reactor.warnings()).isEmpty();
	}

	@Test
	void multiModuleResolvesLocalParentManagementAndInheritedProperties() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>com.example</groupId><artifactId>parent</artifactId><version>2.0</version><packaging>pom</packaging>
				<modules><module>core</module></modules>
				<properties><guava.version>33.0.0-jre</guava.version></properties>
				<dependencyManagement><dependencies>
				  <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
				</dependencies></dependencyManagement>
				""")).add("core/pom.xml", pom("""
				<parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>2.0</version></parent>
				<artifactId>core</artifactId>
				<dependencies>
				  <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
				  <dependency><groupId>com.example</groupId><artifactId>sibling</artifactId><version>${project.version}</version></dependency>
				</dependencies>
				"""));

		MavenReactor reactor = parse(ws);

		MavenModule core = module(reactor, "core/pom.xml");
		assertThat(core.localParent()).isSameAs(module(reactor, "pom.xml"));
		assertThat(module(reactor, "pom.xml").declaredBy()).isNull();
		assertThat(core.declaredBy()).isEqualTo("pom.xml");
		ResolvedDependency guava = dependency(core, "guava");
		assertThat(guava.versionSource()).isEqualTo(VersionSource.MANAGED_LOCAL);
		assertThat(guava.resolvedVersion()).isEqualTo("33.0.0-jre");
		assertThat(guava.declaredVersion()).isNull();
		ResolvedDependency sibling = dependency(core, "sibling");
		assertThat(sibling.versionSource()).isEqualTo(VersionSource.PROPERTY);
		assertThat(sibling.resolvedVersion()).isEqualTo("2.0");
	}

	@Test
	void customPropertyInterpolationAndChildOverride() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>p</artifactId><version>1</version><packaging>pom</packaging>
				<properties><lib.version>1.0</lib.version></properties>
				""")).add("child/pom.xml", pom("""
				<parent><groupId>g</groupId><artifactId>p</artifactId><version>1</version></parent>
				<artifactId>child</artifactId>
				<properties><lib.version>2.0</lib.version></properties>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency></dependencies>
				"""));

		ResolvedDependency lib = dependency(module(parse(ws), "child/pom.xml"), "lib");

		assertThat(lib.resolvedVersion()).isEqualTo("2.0");
		assertThat(lib.declaredVersion()).isEqualTo("${lib.version}");
	}

	@Test
	void unresolvablePlaceholderStaysLiteralAndIsMarkedUnresolved() {
		MavenReactor reactor = parse(new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<dependencies><dependency><groupId>x</groupId><artifactId>lib</artifactId><version>${missing.version}</version></dependency></dependencies>
				""")));

		ResolvedDependency lib = dependency(module(reactor, "pom.xml"), "lib");

		assertThat(lib.versionSource()).isEqualTo(VersionSource.UNRESOLVED);
		assertThat(lib.declaredVersion()).isEqualTo("${missing.version}");
		assertThat(lib.resolvedVersion()).isNull();
		assertThat(lib.note()).contains("${missing.version}");
	}

	@Test
	void remoteParentMarksVersionlessDependenciesAsManagedRemoteUnresolved() {
		MavenReactor reactor = parse(new InMemoryWorkspace().add("pom.xml", pom("""
				<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.1</version></parent>
				<artifactId>demo</artifactId>
				<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
				""")));

		MavenModule root = module(reactor, "pom.xml");
		ResolvedDependency web = dependency(root, "spring-boot-starter-web");

		assertThat(root.localParent()).isNull();
		assertThat(web.versionSource()).isEqualTo(VersionSource.MANAGED_REMOTE_UNRESOLVED);
		assertThat(web.resolvedVersion()).isNull();
		assertThat(web.note()).isEqualTo(
				"managed by parent org.springframework.boot:spring-boot-starter-parent:3.3.1 (not resolved)");
	}

	@Test
	void bomImportIsRecordedButNotFetched() {
		MavenReactor reactor = parse(new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<dependencyManagement><dependencies>
				  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>3.3.1</version><type>pom</type><scope>import</scope></dependency>
				</dependencies></dependencyManagement>
				<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency></dependencies>
				""")));

		MavenModule root = module(reactor, "pom.xml");

		assertThat(root.bomImports()).hasSize(1);
		ResolvedDependency bom = root.managementEntries().get(0);
		assertThat(bom.bomImport()).isTrue();
		assertThat(bom.managementEntry()).isTrue();
		assertThat(bom.note()).contains("not fetched");
		ResolvedDependency starter = dependency(root, "spring-boot-starter");
		assertThat(starter.versionSource()).isEqualTo(VersionSource.MANAGED_REMOTE_UNRESOLVED);
		assertThat(starter.note()).isEqualTo(
				"managed by imported BOM org.springframework.boot:spring-boot-dependencies:3.3.1 (not resolved)");
	}

	@Test
	void profileDependenciesCarryTheirProfileIdAsANote() {
		MavenReactor reactor = parse(new InMemoryWorkspace().add("pom.xml", pom("""
				<groupId>g</groupId><artifactId>a</artifactId><version>1</version>
				<profiles><profile><id>ci</id><dependencies>
				  <dependency><groupId>x</groupId><artifactId>ci-only</artifactId><version>1.2</version></dependency>
				</dependencies></profile></profiles>
				""")));

		ResolvedDependency dependency = dependency(module(reactor, "pom.xml"), "ci-only");

		assertThat(dependency.profileId()).isEqualTo("ci");
		assertThat(dependency.note()).contains("declared in profile ci");
	}

	@Test
	void malformedPomBecomesAWarningNotACrash() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", pom("<groupId>g</groupId><artifactId>ok</artifactId><version>1</version>"))
			.add("broken/pom.xml", "<project><dependencies><dependency></project>");

		MavenReactor reactor = parse(ws);

		assertThat(reactor.modules()).extracting(MavenModule::pomPath).containsExactly("pom.xml");
		assertThat(reactor.warnings()).hasSize(1);
		assertThat(reactor.warnings().get(0)).contains("broken/pom.xml");
	}

	@Test
	void doctypeWithExternalEntityNeverReadsLocalFilesOrTheNetwork(@TempDir Path dir) throws Exception {
		Path secret = dir.resolve("secret.txt");
		Files.writeString(secret, "TOP-SECRET-FILE-CONTENT");
		String xxe = "<?xml version=\"1.0\"?>\n<!DOCTYPE project [\n  <!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">\n"
				+ "  <!ENTITY remote SYSTEM \"http://127.0.0.1:9/should-never-be-called\">\n]>\n"
				+ "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
				+ "<version>1</version><name>&xxe;&remote;</name>"
				+ "<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1</version></dependency></dependencies></project>";

		MavenReactor reactor = parse(new InMemoryWorkspace().add("pom.xml", xxe));

		String everything = reactor.modules().toString() + reactor.warnings()
				+ reactor.modules().stream().map(m -> String.valueOf(m.model().getName())).toList();
		assertThat(everything).doesNotContain("TOP-SECRET-FILE-CONTENT");
	}

	@Test
	void relativePathDefaultsToParentDirectoryPom() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("platform/pom.xml", pom("<groupId>g</groupId><artifactId>platform</artifactId><version>3</version>"))
			.add("platform/svc/pom.xml", pom("<parent><groupId>g</groupId><artifactId>platform</artifactId><version>3</version></parent><artifactId>svc</artifactId>"));

		MavenReactor reactor = parse(ws);

		assertThat(module(reactor, "platform/svc/pom.xml").localParent()).isSameAs(module(reactor, "platform/pom.xml"));
		assertThat(module(reactor, "platform/svc/pom.xml").modulePath()).isEqualTo("platform/svc");
	}

}
