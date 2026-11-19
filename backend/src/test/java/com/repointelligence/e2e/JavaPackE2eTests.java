package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.E2eTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Spring Boot fixture through the whole pipeline: dependency inventory, technologies, capability matrix. */
class JavaPackE2eTests extends E2eTestBase {

	private static final String SHA = "1".repeat(40);

	@Test
	void springBootFixtureProducesTheExpectedDependencyInventory() throws Exception {
		Analysis analysis = analyze("acme", "spring-boot-simple", SHA, fixture("spring-boot-simple").build());

		assertThat(analysis.status()).isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");

		String deps = dependencies(analysis, "&ecosystem=MAVEN");
		List<String> names = JsonPath.read(deps, "$.items[?(@.managementEntry==false)].name");
		assertThat(names).containsExactlyInAnyOrder("spring-boot-starter-web", "spring-boot-starter-data-jpa",
				"postgresql", "commons-lang3", "spring-boot-starter-test");

		// a property-supplied version is resolved from the local POM
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='commons-lang3')].resolvedVersion"))
			.containsExactly("3.14.0");
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='commons-lang3')].versionSource"))
			.containsExactly("PROPERTY");
		// versions supplied by the remote Spring Boot parent are labelled, never guessed
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='spring-boot-starter-web')].versionSource"))
			.containsExactly("MANAGED_REMOTE_UNRESOLVED");
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='spring-boot-starter-web')].note"))
			.containsExactly("managed by parent org.springframework.boot:spring-boot-starter-parent:3.3.1 (not resolved)");
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='spring-boot-starter-web')].vulnerabilityStatus"))
			.containsExactly("NOT_CHECKED");
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='postgresql')].scope")).containsExactly("runtime");
	}

	@Test
	void reportShowsSpringBootJavaAndTheMavenCapabilityRow() throws Exception {
		Analysis analysis = analyze("acme", "spring-boot-simple", SHA, fixture("spring-boot-simple").build());

		String report = report(analysis);

		assertThat((List<String>) JsonPath.read(report, "$.technologies[?(@.name=='Spring Boot')].version"))
			.containsExactly("3.3.1");
		assertThat((List<String>) JsonPath.read(report, "$.technologies[?(@.name=='Java')].version")).containsExactly("21");
		assertThat((List<String>) JsonPath.read(report, "$.technologies[?(@.name=='PostgreSQL')].kind"))
			.containsExactly("DATABASE");
		assertThat((List<String>) JsonPath.read(report,
				"$.capabilityMatrix[?(@.analyzerId=='maven-dependency-analyzer' && @.technology=='Java')].capabilityLevel"))
			.containsExactly("DEEP");
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='DEPENDENCIES')].status"))
			.containsExactly("COMPLETED");
		// remote-managed versions are surfaced as an INFO finding, not hidden
		String findings = findings(analysis, "&category=DEPENDENCIES");
		assertThat((List<String>) JsonPath.read(findings, "$.items[*].ruleId")).contains("maven-remote-managed-versions");
	}

	@Test
	void multiModuleFixtureReportsBomImportVersionInconsistencyAndProfileDependencies() throws Exception {
		Analysis analysis = analyze("acme", "platform", "2".repeat(40), fixture("maven-multi-module").build());

		String deps = dependencies(analysis, "");

		// guava comes from the local parent's dependencyManagement, via a property
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='guava' && @.managementEntry==false)].versionSource"))
			.containsExactly("MANAGED_LOCAL");
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='guava' && @.managementEntry==false)].resolvedVersion"))
			.containsExactly("33.0.0-jre");
		// the BOM import is recorded, not fetched
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='spring-boot-dependencies')].note"))
			.anyMatch(note -> note.contains("not fetched"));
		// ${project.version} resolves through the local parent
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='core')].resolvedVersion")).containsExactly("2.0.0");
		// profile dependency carries its profile id
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='junit-jupiter')].note"))
			.anyMatch(note -> note.contains("declared in profile integration"));
		// the same artifact at two versions in two modules
		String findings = findings(analysis, "&category=DEPENDENCIES");
		assertThat((List<String>) JsonPath.read(findings, "$.items[*].ruleId")).contains("maven-version-inconsistency");
		assertThat((List<String>) JsonPath.read(findings, "$.items[?(@.ruleId=='maven-version-inconsistency')].title"))
			.anyMatch(title -> title.contains("commons-lang3"));
	}

	@Test
	void reactFixtureGetsAnNpmInventoryAndNoJavaRows() throws Exception {
		Analysis analysis = analyze("acme", "react-demo", "3".repeat(40), fixture("react-typescript").build());

		String deps = dependencies(analysis, "&ecosystem=NPM");
		assertThat((List<String>) JsonPath.read(deps, "$.items[*].name")).contains("react", "react-dom", "react-query",
				"typescript", "vite", "vitest");
		assertThat((List<String>) JsonPath.read(deps, "$.items[?(@.name=='react')].declaredVersion")).containsExactly("^19.0.0");
		assertThat((List<Object>) JsonPath.read(deps, "$.items[?(@.name=='react')].resolvedVersion")).containsExactly((Object) null);

		String report = report(analysis);
		assertThat((List<String>) JsonPath.read(report, "$.capabilityMatrix[*].technology")).doesNotContain("Java");
		assertThat((List<String>) JsonPath.read(report, "$.capabilityMatrix[*].analyzerId"))
			.doesNotContain("maven-dependency-analyzer");
	}

}
