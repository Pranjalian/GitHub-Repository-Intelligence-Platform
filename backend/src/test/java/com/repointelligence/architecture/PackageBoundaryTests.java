package com.repointelligence.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Core packages must not depend on language packs (analysis.packs.*): packs plug in, the core never names them. */
class PackageBoundaryTests {

	private static final Path MAIN = Path.of("src/main/java/com/repointelligence");

	@Test
	void corePackagesNeverImportFromLanguagePacks() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().replace('\\', '/').contains("/analysis/packs/"))
				.filter(PackageBoundaryTests::importsAPack)
				.map(Path::toString)
				.toList();

			assertThat(violations).as("files outside analysis.packs that import analysis.packs.*").isEmpty();
		}
	}

	@Test
	void analyzersNeverImportPersistenceTypes() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN.resolve("analysis/packs"))) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> read(p).contains("jakarta.persistence") || read(p).contains("infrastructure.persistence"))
				.map(Path::toString)
				.toList();

			assertThat(violations).as("analyzers return results; only the orchestrator persists").isEmpty();
		}
	}

	@Test
	void nothingOutsideAiProviderUsesAProviderClient() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().replace('\\', '/').contains("/ai/provider/"))
				.filter(p -> {
					String source = read(p);
					return source.contains("import com.repointelligence.ai.provider.ollama")
							|| source.contains("import com.repointelligence.ai.provider.gemini")
							|| source.contains("import com.repointelligence.ai.provider.openrouter")
							|| source.contains("ai.provider.ProviderHttp") || source.contains("ai.provider.ProviderSupport");
				})
				.map(Path::toString)
				.toList();

			assertThat(violations).as("features use the AI gateway and AIModelService, never a provider adapter").isEmpty();
		}
	}

	@Test
	void onlyTheAiModuleTalksToAiProviders() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().replace('\\', '/').contains("/ai/"))
				// RAG (MVP 4) is an AI feature by design (architecture §2.2: rag -> ai). It still reaches models only
				// through the gateway and the provider interfaces (see the provider-client test above).
				.filter(p -> !p.toString().replace('\\', '/').contains("/rag/"))
				// Impact analysis (MVP 5) has an optional AI summary, again only through the gateway and router.
				.filter(p -> !p.toString().replace('\\', '/').contains("/impact/"))
				.filter(p -> read(p).contains("import com.repointelligence.ai."))
				.map(Path::toString)
				.toList();

			assertThat(violations).as("analysis, reports and repositories never depend on AI").isEmpty();
		}
	}

	@Test
	void analysisReportsRepositoriesAndAiNeverDependOnRag() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().replace('\\', '/').contains("/rag/"))
				.filter(p -> read(p).contains("import com.repointelligence.rag."))
				.map(Path::toString)
				.toList();

			assertThat(violations).as("RAG is layered on top; nothing below it imports it").isEmpty();
		}
	}

	@Test
	void nothingBelowImpactAnalysisDependsOnIt() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().replace('\\', '/').contains("/impact/"))
				.filter(p -> read(p).contains("import com.repointelligence.impact."))
				.map(Path::toString)
				.toList();

			assertThat(violations).as("impact analysis is layered on top; nothing else imports it").isEmpty();
		}
	}

	@Test
	void theImpactSpiLivesInTheCoreAndLanguageRulesInThePack() throws IOException {
		assertThat(Files.exists(MAIN.resolve("analysis/spi/ImpactAnalyzer.java"))).isTrue();
		assertThat(Files.exists(MAIN.resolve("analysis/packs/java/JavaImpactAnalyzer.java"))).isTrue();
		try (Stream<Path> files = Files.walk(MAIN.resolve("impact"))) {
			assertThat(files.filter(p -> p.toString().endsWith(".java")).filter(PackageBoundaryTests::importsAPack).toList())
				.as("the impact module reaches language rules only through the ImpactAnalyzer SPI")
				.isEmpty();
		}
	}

	@Test
	void theSecurityPackageDependsOnlyOnCommon() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN.resolve("common/security"))) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> read(p).lines()
					.anyMatch(line -> line.startsWith("import com.repointelligence.")
							&& !line.startsWith("import com.repointelligence.common.")))
				.map(Path::toString)
				.toList();

			assertThat(violations).as("login code reaches no feature package").isEmpty();
		}
	}

	@Test
	void featurePackagesNeverDependOnTheLoginCode() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().replace('\\', '/').contains("/common/security/"))
				.filter(p -> read(p).lines()
					.anyMatch(line -> line.startsWith("import com.repointelligence.common.security.")
							&& !line.equals("import com.repointelligence.common.security.SecretRedactor;")))
				.map(Path::toString)
				.toList();

			assertThat(violations)
				.as("only the filter chain knows about authentication; features see just the shared SecretRedactor")
				.isEmpty();
		}
	}

	private static boolean importsAPack(Path file) {
		return read(file).contains("import com.repointelligence.analysis.packs.");
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
