package com.repointelligence.analysis.packs.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.packs.java.maven.MavenModule;
import com.repointelligence.analysis.packs.java.maven.MavenModule.ResolvedDependency;
import com.repointelligence.analysis.packs.java.maven.MavenPomParser;
import com.repointelligence.analysis.packs.java.maven.MavenReactor;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import org.apache.maven.model.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects Spring Boot, test frameworks, JaCoCo, database technologies and the Java version from POMs,
 * and finds {@code @SpringBootApplication} with a real parser (so a mention in a comment or string
 * does not count). Only reads files: nothing from the repository is executed.
 */
@Component
public class JavaSpringTechDetector implements TechnologyDetector {

	public static final String ID = "java-spring-technology-detector";

	private static final Logger log = LoggerFactory.getLogger(JavaSpringTechDetector.class);

	private static final String VERSION = "1.0.0";

	private static final String SPRING_BOOT = "Spring Boot";

	private static final String BOOT_GROUP = "org.springframework.boot";

	private static final int MAX_JAVA_FILES_PARSED = 2000;

	private static final long MAX_JAVA_FILE_BYTES = 256 * 1024L;

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Java and Spring Technology Detector", VERSION, AnalysisCategory.TECHNOLOGY, CapabilityLevel.BASIC)
		.languages("Java")
		.description("Detects Spring Boot, test frameworks, JaCoCo, databases and the Java version from POMs and "
				+ "@SpringBootApplication (parsed, not matched by regex).")
		.limitations("Versions managed by a remote parent or BOM are not resolved.")
		.build();

	/** One dependency-coordinate rule: which declared artifact implies which technology. */
	private record Rule(BiPredicate<String, String> match, String technology, TechnologyKind kind, Confidence confidence,
			String signal) {

		static Rule artifact(String groupId, String artifactId, String technology, TechnologyKind kind) {
			return new Rule((g, a) -> groupId.equals(g) && artifactId.equals(a), technology, kind, Confidence.DETECTED,
					groupId + ":" + artifactId);
		}

		static Rule group(String groupId, String technology, TechnologyKind kind) {
			return new Rule((g, a) -> groupId.equals(g), technology, kind, Confidence.DETECTED, groupId + ":*");
		}

	}

	private static final List<Rule> RULES = List.of(
			// test frameworks
			Rule.artifact("junit", "junit", "JUnit 4", TechnologyKind.TEST_FRAMEWORK),
			Rule.group("org.junit.jupiter", "JUnit 5", TechnologyKind.TEST_FRAMEWORK),
			new Rule((g, a) -> BOOT_GROUP.equals(g) && "spring-boot-starter-test".equals(a), "JUnit 5",
					TechnologyKind.TEST_FRAMEWORK, Confidence.LIKELY, "spring-boot-starter-test (includes JUnit 5)"),
			new Rule((g, a) -> BOOT_GROUP.equals(g) && "spring-boot-starter-test".equals(a), "Mockito",
					TechnologyKind.TEST_FRAMEWORK, Confidence.LIKELY, "spring-boot-starter-test (includes Mockito)"),
			Rule.artifact("org.testng", "testng", "TestNG", TechnologyKind.TEST_FRAMEWORK),
			Rule.group("org.mockito", "Mockito", TechnologyKind.TEST_FRAMEWORK),
			Rule.group("org.testcontainers", "Testcontainers", TechnologyKind.TEST_FRAMEWORK),
			// databases and persistence
			Rule.artifact("org.postgresql", "postgresql", "PostgreSQL", TechnologyKind.DATABASE),
			Rule.artifact("com.mysql", "mysql-connector-j", "MySQL", TechnologyKind.DATABASE),
			Rule.artifact("mysql", "mysql-connector-java", "MySQL", TechnologyKind.DATABASE),
			Rule.artifact("org.mariadb.jdbc", "mariadb-java-client", "MariaDB", TechnologyKind.DATABASE),
			Rule.artifact("com.h2database", "h2", "H2", TechnologyKind.DATABASE),
			Rule.artifact("org.hsqldb", "hsqldb", "HSQLDB", TechnologyKind.DATABASE),
			Rule.artifact("com.microsoft.sqlserver", "mssql-jdbc", "SQL Server", TechnologyKind.DATABASE),
			Rule.artifact("org.xerial", "sqlite-jdbc", "SQLite", TechnologyKind.DATABASE),
			Rule.group("com.oracle.database.jdbc", "Oracle Database", TechnologyKind.DATABASE),
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-data-mongodb", "MongoDB", TechnologyKind.DATABASE),
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-data-redis", "Redis", TechnologyKind.DATABASE),
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-data-jpa", "Spring Data JPA", TechnologyKind.FRAMEWORK),
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-data-jdbc", "Spring Data JDBC", TechnologyKind.FRAMEWORK),
			Rule.artifact("org.flywaydb", "flyway-core", "Flyway", TechnologyKind.FRAMEWORK),
			Rule.artifact("org.liquibase", "liquibase-core", "Liquibase", TechnologyKind.FRAMEWORK),
			// web
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-web", "Spring MVC", TechnologyKind.FRAMEWORK),
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-webmvc", "Spring MVC", TechnologyKind.FRAMEWORK),
			Rule.artifact(BOOT_GROUP, "spring-boot-starter-webflux", "Spring WebFlux", TechnologyKind.FRAMEWORK));

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public List<DetectedTechnology> detect(AnalysisContext context) {
		Map<String, Detection> found = new LinkedHashMap<>();
		MavenReactor reactor = MavenPomParser.parse(context.workspace(), context.files());

		for (MavenModule module : reactor.modules()) {
			detectFromPom(module, found);
		}
		detectSpringBootApplication(context, found);
		if (found.containsKey(SPRING_BOOT)) {
			for (FileInfo file : context.files()) {
				if (isSpringConfig(file)) {
					found.get(SPRING_BOOT).evidence.add(new Evidence(file.path(), null, "Spring configuration file"));
				}
			}
		}
		else if (reactor.modules().stream().anyMatch(this::declaresSpringFramework)) {
			MavenModule module = reactor.modules().stream().filter(this::declaresSpringFramework).findFirst().orElseThrow();
			found.put("Spring Framework", new Detection("Spring Framework", TechnologyKind.FRAMEWORK, Confidence.DETECTED,
					null, new ArrayList<>(List.of(new Evidence(module.pomPath(), null, "org.springframework dependency")))));
		}

		return found.values().stream().map(Detection::toTechnology).toList();
	}

	private boolean declaresSpringFramework(MavenModule module) {
		return module.dependencies().stream().anyMatch(d -> "org.springframework".equals(d.groupId()));
	}

	private void detectFromPom(MavenModule module, Map<String, Detection> found) {
		String pom = module.pomPath();
		List<ResolvedDependency> dependencies = module.dependencies();

		// Spring Boot: starters, parent, BOM, plugin
		boolean springBoot = false;
		for (ResolvedDependency dependency : dependencies) {
			if (BOOT_GROUP.equals(dependency.groupId()) && dependency.artifactId().startsWith("spring-boot-starter")) {
				springBoot = true;
				add(found, SPRING_BOOT, TechnologyKind.FRAMEWORK, Confidence.DETECTED, null,
						new Evidence(pom, dependency.line(), dependency.coordinate()));
			}
		}
		var parent = module.model().getParent();
		if (parent != null && BOOT_GROUP.equals(parent.getGroupId())
				&& "spring-boot-starter-parent".equals(parent.getArtifactId())) {
			springBoot = true;
			add(found, SPRING_BOOT, TechnologyKind.FRAMEWORK, Confidence.DETECTED, module.interpolate(parent.getVersion()).value(),
					new Evidence(pom, null, "parent spring-boot-starter-parent " + parent.getVersion()));
		}
		for (Dependency managed : module.bomImports()) {
			if (BOOT_GROUP.equals(managed.getGroupId()) && "spring-boot-dependencies".equals(managed.getArtifactId())) {
				springBoot = true;
				add(found, SPRING_BOOT, TechnologyKind.FRAMEWORK, Confidence.DETECTED,
						module.interpolate(managed.getVersion()).value(),
						new Evidence(pom, null, "BOM spring-boot-dependencies " + managed.getVersion()));
			}
		}
		if (module.hasPlugin(BOOT_GROUP, "spring-boot-maven-plugin")) {
			springBoot = true;
			add(found, SPRING_BOOT, TechnologyKind.FRAMEWORK, Confidence.DETECTED,
					module.pluginVersion(BOOT_GROUP, "spring-boot-maven-plugin").orElse(null),
					new Evidence(pom, null, "spring-boot-maven-plugin"));
		}
		if (springBoot) {
			String propertyVersion = module.properties().get("spring-boot.version");
			if (propertyVersion != null) {
				add(found, SPRING_BOOT, TechnologyKind.FRAMEWORK, Confidence.DETECTED,
						module.interpolate(propertyVersion).value(), new Evidence(pom, null, "property spring-boot.version"));
			}
		}

		// coordinate rules
		for (ResolvedDependency dependency : dependencies) {
			for (Rule rule : RULES) {
				if (rule.match().test(dependency.groupId(), dependency.artifactId())) {
					add(found, rule.technology(), rule.kind(), rule.confidence(), null,
							new Evidence(pom, dependency.line(), rule.signal()));
				}
			}
		}
		if (module.hasPlugin("org.jacoco", "jacoco-maven-plugin")) {
			add(found, "JaCoCo", TechnologyKind.TEST_FRAMEWORK, Confidence.DETECTED,
					module.pluginVersion("org.jacoco", "jacoco-maven-plugin").orElse(null),
					new Evidence(pom, null, "jacoco-maven-plugin"));
		}

		// Java version
		Optional<String> java = module.javaVersion();
		java.ifPresent(version -> add(found, "Java", TechnologyKind.LANGUAGE, Confidence.DETECTED, version,
				new Evidence(pom, null, "Java version " + version + " declared")));
	}

	private void detectSpringBootApplication(AnalysisContext context, Map<String, Detection> found) {
		JavaParser parser = new JavaParser(new ParserConfiguration()
			.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
			.setAttributeComments(false));
		int parsed = 0;
		for (FileInfo file : context.files()) {
			if (!file.extracted() || !file.path().endsWith(".java") || file.sizeBytes() > MAX_JAVA_FILE_BYTES) {
				continue;
			}
			if (parsed >= MAX_JAVA_FILES_PARSED) {
				break;
			}
			context.cancellation().throwIfCancelled();
			String source;
			try {
				source = context.workspace().readString(file.path(), MAX_JAVA_FILE_BYTES);
			}
			catch (IOException | RuntimeException ex) {
				log.debug("Cannot read {}: {}", file.path(), ex.getMessage());
				continue;
			}
			// cheap pre-filter only: the decision is made on the AST below
			if (source == null || !source.contains("SpringBootApplication")) {
				continue;
			}
			parsed++;
			Optional<CompilationUnit> unit = parser.parse(source).getResult();
			if (unit.isEmpty()) {
				continue;
			}
			for (TypeDeclaration<?> type : unit.get().getTypes()) {
				for (AnnotationExpr annotation : type.getAnnotations()) {
					String name = annotation.getNameAsString();
					if (name.equals("SpringBootApplication") || name.endsWith(".SpringBootApplication")) {
						int line = annotation.getRange().map(range -> range.begin.line).orElse(1);
						add(found, SPRING_BOOT, TechnologyKind.FRAMEWORK, Confidence.DETECTED, null,
								new Evidence(file.path(), line, "@SpringBootApplication on " + type.getNameAsString()));
					}
				}
			}
		}
	}

	private static boolean isSpringConfig(FileInfo file) {
		String name = file.path().substring(file.path().lastIndexOf('/') + 1);
		return name.matches("application(-[A-Za-z0-9_]+)?\\.(ya?ml|properties)");
	}

	private static void add(Map<String, Detection> found, String name, TechnologyKind kind, Confidence confidence,
			String version, Evidence evidence) {
		Detection detection = found.computeIfAbsent(name,
				key -> new Detection(name, kind, confidence, null, new ArrayList<>()));
		if (version != null && detection.version == null) {
			detection.version = version;
		}
		if (confidence.ordinal() < detection.confidence.ordinal()) {
			detection.confidence = confidence;
		}
		if (!detection.evidence.contains(evidence)) {
			detection.evidence.add(evidence);
		}
	}

	private static final class Detection {

		private final String name;

		private final TechnologyKind kind;

		private Confidence confidence;

		private String version;

		private final List<Evidence> evidence;

		Detection(String name, TechnologyKind kind, Confidence confidence, String version, List<Evidence> evidence) {
			this.name = name;
			this.kind = kind;
			this.confidence = confidence;
			this.version = version;
			this.evidence = evidence;
		}

		DetectedTechnology toTechnology() {
			return new DetectedTechnology(name, kind, version, confidence, evidence, ID, null);
		}

	}

}
