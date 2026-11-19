package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.support.InMemoryWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaSpringTechDetectorTests {

	private final JavaSpringTechDetector detector = new JavaSpringTechDetector();

	private static String pom(String body) {
		return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>" + body + "</project>";
	}

	private static DetectedTechnology find(List<DetectedTechnology> all, String name) {
		return all.stream().filter(t -> t.technology().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError(name + " not detected in " + all.stream().map(DetectedTechnology::technology).toList()));
	}

	private static final String BOOT_POM = pom("""
			<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.1</version></parent>
			<groupId>com.example</groupId><artifactId>demo</artifactId>
			<properties><java.version>21</java.version></properties>
			<dependencies>
			  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
			  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
			  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
			  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
			  <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
			</dependencies>
			<build><plugins>
			  <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
			  <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version></plugin>
			</plugins></build>
			""");

	@Test
	void findsSpringBootVersionFromTheLocalParentDeclaration() {
		List<DetectedTechnology> result = detector.detect(new InMemoryWorkspace().add("pom.xml", BOOT_POM).context());

		DetectedTechnology boot = find(result, "Spring Boot");
		assertThat(boot.version()).isEqualTo("3.3.1");
		assertThat(boot.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(boot.kind()).isEqualTo(TechnologyKind.FRAMEWORK);
		assertThat(boot.evidence()).extracting(e -> e.path()).contains("pom.xml");
	}

	@Test
	void detectsTestFrameworksJacocoDatabasesAndJavaVersion() {
		List<DetectedTechnology> result = detector.detect(new InMemoryWorkspace().add("pom.xml", BOOT_POM).context());

		assertThat(find(result, "JUnit 5").kind()).isEqualTo(TechnologyKind.TEST_FRAMEWORK);
		assertThat(find(result, "Mockito").confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(find(result, "Testcontainers").confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(find(result, "JaCoCo").version()).isEqualTo("0.8.12");
		assertThat(find(result, "PostgreSQL").kind()).isEqualTo(TechnologyKind.DATABASE);
		assertThat(find(result, "Spring Data JPA").kind()).isEqualTo(TechnologyKind.FRAMEWORK);
		assertThat(find(result, "Spring MVC")).isNotNull();
		DetectedTechnology java = find(result, "Java");
		assertThat(java.version()).isEqualTo("21");
		assertThat(java.kind()).isEqualTo(TechnologyKind.LANGUAGE);
	}

	@Test
	void readsTheJavaVersionFromMavenCompilerRelease() {
		String pom = pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"
				+ "<properties><maven.compiler.release>17</maven.compiler.release></properties>");

		assertThat(find(detector.detect(new InMemoryWorkspace().add("pom.xml", pom).context()), "Java").version())
			.isEqualTo("17");
	}

	@Test
	void findsSpringBootApplicationFromTheAstAndRecordsTheLine() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/main/java/com/example/DemoApplication.java", """
				package com.example;

				import org.springframework.boot.autoconfigure.SpringBootApplication;

				@SpringBootApplication
				public class DemoApplication {
					public static void main(String[] args) {}
				}
				""");

		DetectedTechnology boot = find(detector.detect(ws.context()), "Spring Boot");

		assertThat(boot.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(boot.evidence()).anySatisfy(e -> {
			assertThat(e.path()).endsWith("DemoApplication.java");
			assertThat(e.line()).isEqualTo(5);
			assertThat(e.signal()).contains("@SpringBootApplication");
		});
	}

	@Test
	void ignoresSpringBootApplicationInsideACommentOrString() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/main/java/Notes.java", """
				package x;

				// @SpringBootApplication is mentioned here only
				/* @SpringBootApplication */
				public class Notes {
					String s = "@SpringBootApplication";
				}
				""");

		assertThat(detector.detect(ws.context())).extracting(DetectedTechnology::technology).doesNotContain("Spring Boot");
	}

	@Test
	void springConfigurationFilesBecomeEvidenceForSpringBoot() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pom.xml", BOOT_POM).add("src/main/resources/application.yml", "server:\n  port: 8080\n");

		assertThat(find(detector.detect(ws.context()), "Spring Boot").evidence())
			.anySatisfy(e -> assertThat(e.path()).isEqualTo("src/main/resources/application.yml"));
	}

	@Test
	void nonSpringJavaProjectIsNotReportedAsSpring() {
		String pom = pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"
				+ "<dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency></dependencies>");

		List<DetectedTechnology> result = detector.detect(new InMemoryWorkspace().add("pom.xml", pom).context());

		assertThat(result).extracting(DetectedTechnology::technology).contains("JUnit 4").doesNotContain("Spring Boot");
	}

	@Test
	void repositoriesWithoutJavaYieldNothing() {
		assertThat(detector.detect(new InMemoryWorkspace().add("package.json", "{}").add("src/index.ts", "export {}").context()))
			.isEmpty();
	}

}
