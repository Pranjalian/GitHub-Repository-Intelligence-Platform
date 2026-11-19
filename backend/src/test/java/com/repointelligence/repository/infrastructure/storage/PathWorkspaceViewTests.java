package com.repointelligence.repository.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathWorkspaceViewTests {

	@TempDir
	Path temp;

	private Path root;

	private WorkspaceView view;

	@BeforeEach
	void setUp() throws IOException {
		root = temp.resolve("snapshot");
		Files.createDirectories(root.resolve("src/main/java"));
		Files.createDirectories(root.resolve("modules/api"));
		Files.writeString(root.resolve("pom.xml"), "<project/>");
		Files.writeString(root.resolve("modules/api/pom.xml"), "<module/>");
		Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");
		Files.writeString(root.resolve("long.txt"), "0123456789".repeat(100));
		Files.writeString(temp.resolve("secret.txt"), "outside the workspace");
		view = new PathWorkspaceView(root);
	}

	@Test
	void listsFilesByGlobSortedWithForwardSlashes() throws IOException {
		// "**" matches zero or more directories, so the root manifest is included.
		assertThat(view.list("**/pom.xml")).containsExactly("modules/api/pom.xml", "pom.xml");
		assertThat(view.list("pom.xml")).containsExactly("pom.xml");
		assertThat(view.list("src/**/*.java")).containsExactly("src/main/java/App.java");
		assertThat(view.list("**")).containsExactly("long.txt", "modules/api/pom.xml", "pom.xml",
				"src/main/java/App.java");
		assertThat(view.list("**/*.go")).isEmpty();
	}

	@Test
	void readsTextAndHonoursTheByteLimit() throws IOException {
		assertThat(view.readString("pom.xml", 1000)).isEqualTo("<project/>");
		assertThat(view.readString("long.txt", 25)).isEqualTo("0123456789012345678901234");
	}

	@Test
	void opensAStream() throws IOException {
		try (InputStream in = view.open("src/main/java/App.java")) {
			assertThat(new String(in.readAllBytes())).isEqualTo("class App {}");
		}
	}

	@Test
	void existsOnlyForRegularFilesInsideTheWorkspace() {
		assertThat(view.exists("pom.xml")).isTrue();
		assertThat(view.exists("src")).isFalse();
		assertThat(view.exists("missing.txt")).isFalse();
		assertThat(view.exists("../secret.txt")).isFalse();
	}

	@Test
	void missingFilesAreReported() {
		assertThatThrownBy(() -> view.readString("missing.txt", 10)).isInstanceOf(NoSuchFileException.class);
		assertThatThrownBy(() -> view.open("src")).isInstanceOf(NoSuchFileException.class);
	}

	@Test
	void pathsThatEscapeTheWorkspaceAreRefused() {
		for (String path : new String[] { "../secret.txt", "src/../../secret.txt", "/etc/passwd", "\\windows\\win.ini",
				"C:/Windows/win.ini", "src\\..\\..\\secret.txt", "", "  ", ".", "src/.." }) {
			assertThatThrownBy(() -> view.readString(path, 100)).as(path).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> view.open(path)).as(path).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void exposesTheDirectoryForExternalTools() {
		assertThat(view.resolveForTool()).isEqualTo(root.toAbsolutePath().normalize());
	}

	@Test
	void doesNotFollowSymbolicLinksOutOfTheWorkspace() throws IOException {
		Path link = root.resolve("escape.txt");
		try {
			Files.createSymbolicLink(link, temp.resolve("secret.txt"));
		}
		catch (IOException | UnsupportedOperationException | SecurityException ex) {
			Assumptions.abort("Symbolic links are not available here: " + ex.getClass().getSimpleName());
		}

		assertThat(view.exists("escape.txt")).isFalse();
		assertThatThrownBy(() -> view.readString("escape.txt", 100)).isInstanceOf(IOException.class);
		assertThat(view.list("**")).doesNotContain("escape.txt");
	}

}
