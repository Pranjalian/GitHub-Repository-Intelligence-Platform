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
