package com.repointelligence.repository.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.common.exception.AnalysisLimitException;
import com.repointelligence.support.TarGzBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The extractor treats the archive as hostile; these use malicious fixture tarballs. */
class SafeTarExtractorTests {

	private static final List<String> EXCLUDED = List.of(".git", "node_modules", "target", "build", "dist", ".gradle",
			"vendor", "__pycache__", ".idea", ".vscode", ".venv");

	private static final List<String> EXCEPTIONS = List.of("**/jacoco*.xml", "**/jacoco/*.xml");

	private final SafeTarExtractor extractor = new SafeTarExtractor();

	@TempDir
	Path temp;

	private Path workspace;

	@BeforeEach
	void setUp() throws IOException {
		// A sibling-free layout: anything written outside "workspace" is visible directly under "temp".
		workspace = temp.resolve("workspace");
		Files.createDirectories(workspace);
	}

	private ExtractionLimits limits() {
		return new ExtractionLimits(10_000, 5_000_000, 1_000, 10_000, EXCLUDED, EXCEPTIONS);
	}

	private ExtractionResult extract(TarGzBuilder builder) throws IOException {
		return extractor.extract(builder.writeTo(temp.resolve("archive.tar.gz")), workspace, limits());
	}

	private static Map<String, ExtractedEntry> byPath(ExtractionResult result) {
		return result.entries().stream().collect(Collectors.toMap(ExtractedEntry::path, Function.identity()));
	}

	// ---- normal behaviour ----------------------------------------------------------------------

	@Test
	void stripsTheTopLevelDirectoryAndCatalogsFilesWithHashes() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().dir("src")
			.file("src/Main.java", "class Main {}")
			.file("pom.xml", "<project/>")
			.file("empty.txt", "");

		ExtractionResult result = extract(archive);

		assertThat(Files.readString(workspace.resolve("src/Main.java"))).isEqualTo("class Main {}");
		assertThat(workspace.resolve("octocat-hello-0123456")).doesNotExist();
		Map<String, ExtractedEntry> entries = byPath(result);
		assertThat(entries).containsOnlyKeys("src/Main.java", "pom.xml", "empty.txt");
		assertThat(entries.get("src/Main.java").sha256()).isEqualTo(sha256("class Main {}"));
		assertThat(entries.get("src/Main.java").extracted()).isTrue();
		assertThat(entries.get("empty.txt").sizeBytes()).isZero();
		assertThat(entries.get("empty.txt").extracted()).isTrue();
		assertThat(result.totalDecompressedBytes()).isEqualTo("class Main {}".length() + "<project/>".length());
	}

	@Test
	void duplicateEntriesKeepTheLastOne() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().file("a.txt", "first").file("a.txt", "second");

		ExtractionResult result = extract(archive);

		assertThat(result.entries()).hasSize(1);
		assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("second");
		assertThat(result.entries().get(0).sha256()).isEqualTo(sha256("second"));
	}

	// ---- exclusions ----------------------------------------------------------------------------

	@Test
	void excludedDirectoriesAreSkippedAtAnyDepthAndNotCatalogued() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().file("src/App.java", "class App {}")
			.file("node_modules/left-pad/index.js", "x")
			.file("frontend/node_modules/y.js", "y")
			.file("target/classes/App.class", "z")
			.file(".git/config", "[core]")
			.file("build/out.txt", "o")
			.file("src/main/dist/bundle.js", "b");

		ExtractionResult result = extract(archive);

		assertThat(byPath(result)).containsOnlyKeys("src/App.java");
		assertThat(result.excludedEntries()).isEqualTo(6);
		assertThat(workspace.resolve("node_modules")).doesNotExist();
		assertThat(workspace.resolve("target")).doesNotExist();
	}

	@Test
	void allowlistedAnalyzerInputsSurviveInsideExcludedDirectories() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().file("target/site/jacoco/jacoco.xml", "<report/>")
			.file("module/target/jacoco-report.xml", "<report/>")
			.file("target/classes/Other.xml", "<other/>")
			.file("target/surefire-reports/TEST-x.xml", "<t/>");

		ExtractionResult result = extract(archive);

		assertThat(byPath(result)).containsOnlyKeys("target/site/jacoco/jacoco.xml", "module/target/jacoco-report.xml");
		assertThat(Files.readString(workspace.resolve("target/site/jacoco/jacoco.xml"))).isEqualTo("<report/>");
	}

	// ---- hostile paths -------------------------------------------------------------------------

	@Test
	void pathTraversalRejectsTheArchiveAndWritesNothingOutside() {
		TarGzBuilder archive = new TarGzBuilder().file("ok.txt", "fine")
			.rawFile("octocat-hello-0123456/../../evil.txt", "pwned".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> extract(archive)).isInstanceOf(UnsafeArchiveException.class)
			.hasMessageContaining("path traversal");

		assertNothingOutsideWorkspace();
	}

	@Test
	void nestedTraversalIsRejectedToo() {
		TarGzBuilder archive = new TarGzBuilder()
			.rawFile("octocat-hello-0123456/a/b/../../../evil.txt", "x".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> extract(archive)).isInstanceOf(UnsafeArchiveException.class);

		assertNothingOutsideWorkspace();
	}

	@Test
	void absolutePathsAreRejected() {
		TarGzBuilder archive = new TarGzBuilder(null).rawFile("/tmp/evil.txt", "x".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> extract(archive)).isInstanceOf(UnsafeArchiveException.class)
			.hasMessageContaining("absolute path");

		assertNothingOutsideWorkspace();
	}

	@Test
	void traversalAsTheTopLevelEntryIsRejected() {
		TarGzBuilder archive = new TarGzBuilder(null).rawFile("../evil.txt", "x".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> extract(archive)).isInstanceOf(UnsafeArchiveException.class);

		assertNothingOutsideWorkspace();
	}

	@Test
	void anArchiveWithTwoTopLevelDirectoriesIsRejected() {
		TarGzBuilder archive = new TarGzBuilder().file("a.txt", "x")
			.rawFile("another-root/b.txt", "y".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> extract(archive)).isInstanceOf(UnsafeArchiveException.class)
			.hasMessageContaining("single top-level directory");
	}

	@Test
	void awkwardFileNamesAreRecordedAsSkippedAndNeverWritten() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().file("con.txt", "device name")
			.file("aux.java", "device name")
			.file("stream:hidden.txt", "alternate stream")
			.file("trailing-dot.", "x")
			.file("fine.txt", "ok");

		ExtractionResult result = extract(archive);

		Map<String, ExtractedEntry> entries = byPath(result);
		for (String name : List.of("con.txt", "aux.java", "stream:hidden.txt", "trailing-dot.")) {
			assertThat(entries.get(name).skippedReason()).as(name).isEqualTo("unsafe file name");
			assertThat(entries.get(name).extracted()).isFalse();
		}
		assertThat(entries.get("fine.txt").extracted()).isTrue();
		try (Stream<Path> files = Files.list(workspace)) {
			assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("fine.txt");
		}
	}

	// ---- links and special files ---------------------------------------------------------------

	@Test
	void linksAndSpecialFilesAreRecordedAsSkippedAndNeverCreated() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().file("real.txt", "real")
			.symlink("passwd-link", "/etc/passwd")
			.symlink("docs/up-link", "../../../outside")
			.hardlink("hard-link", "real.txt")
			.characterDevice("dev-null")
			.fifo("pipe");

		ExtractionResult result = extract(archive);

		Map<String, ExtractedEntry> entries = byPath(result);
		assertThat(entries.get("passwd-link").skippedReason()).isEqualTo("symbolic link");
		assertThat(entries.get("docs/up-link").skippedReason()).isEqualTo("symbolic link");
		assertThat(entries.get("hard-link").skippedReason()).isEqualTo("hard link");
		assertThat(entries.get("dev-null").skippedReason()).isEqualTo("unsupported entry type");
		assertThat(entries.get("pipe").skippedReason()).isEqualTo("unsupported entry type");
		for (String name : List.of("passwd-link", "docs/up-link", "hard-link", "dev-null", "pipe")) {
			assertThat(Files.exists(workspace.resolve(name), LinkOption.NOFOLLOW_LINKS)).as(name).isFalse();
			assertThat(entries.get(name).extracted()).isFalse();
		}
		assertThat(Files.readString(workspace.resolve("real.txt"))).isEqualTo("real");
		assertNothingOutsideWorkspace();
	}

	// ---- size and count limits -----------------------------------------------------------------

	@Test
	void oversizedFilesAreCatalogedButNotExtracted() throws IOException {
		byte[] big = new byte[20_000];
		Arrays.fill(big, (byte) 'a');
		TarGzBuilder archive = new TarGzBuilder().file("big.txt", big).file("small.txt", "small");

		ExtractionResult result = extract(archive);

		ExtractedEntry bigEntry = byPath(result).get("big.txt");
		assertThat(bigEntry.skippedReason()).isEqualTo("exceeds max-file-size-kb (9 KB)");
		assertThat(bigEntry.sizeBytes()).isEqualTo(20_000);
		assertThat(bigEntry.sha256()).isNotNull();
		assertThat(bigEntry.extracted()).isFalse();
		assertThat(workspace.resolve("big.txt")).doesNotExist();
		assertThat(workspace.resolve("small.txt")).exists();
	}

	@Test
	void binaryFilesAreFlaggedHashedAndNotWritten() throws IOException {
		byte[] binary = new byte[] { 'P', 'N', 'G', 0, 1, 2, 3, 0 };
		TarGzBuilder archive = new TarGzBuilder().file("logo.png", binary).file("text.txt", "plain text");

		ExtractionResult result = extract(archive);

		ExtractedEntry logo = byPath(result).get("logo.png");
		assertThat(logo.binary()).isTrue();
		assertThat(logo.skippedReason()).isNull();
		assertThat(logo.extracted()).isFalse();
		assertThat(logo.sha256()).isEqualTo(HexFormat.of().formatHex(sha256Bytes(binary)));
		assertThat(workspace.resolve("logo.png")).doesNotExist();
		assertThat(byPath(result).get("text.txt").binary()).isFalse();
		assertThat(workspace.resolve("text.txt")).exists();
	}

	@Test
	void decompressionBombIsCutOffBeforeAnythingIsWritten() throws IOException {
		// 30 MB of zeros compresses to a few KB, but declares far more than the 5 MB total limit.
		TarGzBuilder archive = new TarGzBuilder().file("bomb.bin", new byte[30_000_000]);
		Path tarball = archive.writeTo(temp.resolve("bomb.tar.gz"));
		assertThat(Files.size(tarball)).isLessThan(100_000);

		assertThatThrownBy(() -> extractor.extract(tarball, workspace, limits())).isInstanceOf(AnalysisLimitException.class)
			.hasMessageContaining("max-extracted-size-mb");

		assertThat(directoryContents(workspace)).isEmpty();
	}

	@Test
	void manySmallFilesAddingUpPastTheTotalLimitAreRejected() {
		TarGzBuilder archive = new TarGzBuilder();
		byte[] chunk = new byte[900_000];
		for (int i = 0; i < 8; i++) {
			archive.file("part" + i + ".txt", chunk);
		}

		assertThatThrownBy(() -> extractor.extract(archive.writeTo(temp.resolve("many.tar.gz")), workspace,
				new ExtractionLimits(1_000_000, 5_000_000, 1000, 10_000, EXCLUDED, EXCEPTIONS)))
			.isInstanceOf(AnalysisLimitException.class)
			.hasMessageContaining("max-extracted-size-mb");
	}

	@Test
	void bytesInsideExcludedDirectoriesStillCountTowardsTheBombLimit() {
		TarGzBuilder archive = new TarGzBuilder().file("node_modules/huge.bin", new byte[6_000_000]);

		assertThatThrownBy(() -> extract(archive)).isInstanceOf(AnalysisLimitException.class)
			.hasMessageContaining("max-extracted-size-mb");
	}

	@Test
	void tooManyArchiveEntriesAreRejected() {
		TarGzBuilder archive = new TarGzBuilder();
		for (int i = 0; i < 300; i++) {
			archive.file("f" + i + ".txt", "x");
		}

		assertThatThrownBy(() -> extractor.extract(archive.writeTo(temp.resolve("entries.tar.gz")), workspace,
				new ExtractionLimits(10_000, 5_000_000, 1_000, 100, EXCLUDED, EXCEPTIONS)))
			.isInstanceOf(AnalysisLimitException.class)
			.hasMessageContaining("more than 100 entries");
	}

	@Test
	void tooManyCataloguedFilesAreRejectedAndNamedByTheirLimit() {
		TarGzBuilder archive = new TarGzBuilder();
		for (int i = 0; i < 50; i++) {
			archive.file("f" + i + ".txt", "x");
		}

		assertThatThrownBy(() -> extractor.extract(archive.writeTo(temp.resolve("files.tar.gz")), workspace,
				new ExtractionLimits(10_000, 5_000_000, 10, 10_000, EXCLUDED, EXCEPTIONS)))
			.isInstanceOf(AnalysisLimitException.class)
			.hasMessageContaining("more than 10 files")
			.hasMessageContaining("max-file-count");
	}

	@Test
	void excludedFilesDoNotCountAgainstTheFileLimit() throws IOException {
		TarGzBuilder archive = new TarGzBuilder().file("keep.txt", "k");
		for (int i = 0; i < 50; i++) {
			archive.file("node_modules/p" + i + "/index.js", "x");
		}

		ExtractionResult result = extractor.extract(archive.writeTo(temp.resolve("nm.tar.gz")), workspace,
				new ExtractionLimits(10_000, 5_000_000, 10, 10_000, EXCLUDED, EXCEPTIONS));

		assertThat(result.entries()).hasSize(1);
		assertThat(result.excludedEntries()).isEqualTo(50);
	}

	@Test
	void truncatedArchiveFailsWithAnIoError() throws IOException {
		byte[] whole = new TarGzBuilder().file("a.txt", "x".repeat(5000)).build();
		Path truncated = temp.resolve("cut.tar.gz");
		Files.write(truncated, Arrays.copyOf(whole, whole.length / 2));

		assertThatThrownBy(() -> extractor.extract(truncated, workspace, limits())).isInstanceOf(IOException.class);
	}

	@Test
	void notAGzipFileFailsWithAnIoError() throws IOException {
		Path garbage = temp.resolve("garbage.tar.gz");
		Files.writeString(garbage, "this is not a tarball");

		assertThatThrownBy(() -> extractor.extract(garbage, workspace, limits())).isInstanceOf(IOException.class);
	}

	// ---- helpers -------------------------------------------------------------------------------

	/** Everything this test creates lives in {@code temp}; only "workspace" and the archives may be there. */
	private void assertNothingOutsideWorkspace() {
		try (Stream<Path> top = Files.walk(temp)) {
			List<String> unexpected = top.filter(Files::isRegularFile)
				.filter(p -> !p.startsWith(workspace))
				.map(p -> temp.relativize(p).toString())
				.filter(name -> !name.endsWith(".tar.gz"))
				.toList();
			assertThat(unexpected).isEmpty();
		}
		catch (IOException ex) {
			throw new AssertionError(ex);
		}
		// Parents of the temp dir must not have received a file named like the attack payloads either.
		assertThat(temp.getParent().resolve("evil.txt")).doesNotExist();
		assertThat(temp.resolve("evil.txt")).doesNotExist();
	}

	private static List<String> directoryContents(Path directory) throws IOException {
		try (Stream<Path> files = Files.walk(directory)) {
			return files.filter(p -> !p.equals(directory)).map(Path::toString).toList();
		}
	}

	private static String sha256(String text) {
		return HexFormat.of().formatHex(sha256Bytes(text.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] sha256Bytes(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
