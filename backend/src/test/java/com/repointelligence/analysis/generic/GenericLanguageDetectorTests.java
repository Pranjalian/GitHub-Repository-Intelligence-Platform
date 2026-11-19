package com.repointelligence.analysis.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.FileInfo;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.repository.domain.FileType;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GenericLanguageDetectorTests {

	private final GenericLanguageDetector detector = new GenericLanguageDetector();

	// ── Helpers ───────────────────────────────────────────────────────────────────────────────────

	private static FileInfo source(String path, String language, long sizeBytes) {
		return new FileInfo(path, language, FileType.SOURCE, sizeBytes, "hash", false, null);
	}

	private static FileInfo test(String path, String language, long sizeBytes) {
		return new FileInfo(path, language, FileType.TEST, sizeBytes, "hash", false, null);
	}

	private static FileInfo docs(String path, long sizeBytes) {
		return new FileInfo(path, "Markdown", FileType.DOCS, sizeBytes, "hash", false, null);
	}

	private static FileInfo binary(String path, long sizeBytes) {
		return new FileInfo(path, null, FileType.OTHER, sizeBytes, "hash", true, null);
	}

	private static FileInfo skipped(String path, long sizeBytes) {
		return new FileInfo(path, "Java", FileType.SOURCE, sizeBytes, "hash", false, "too large");
	}

	private AnalysisContext ctx(List<FileInfo> files) {
		return new AnalysisContext(null, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(40),
				mock(WorkspaceView.class), files, List.of(), AnalyzerSettings.defaults(), null, null);
	}

	// ── Tests ─────────────────────────────────────────────────────────────────────────────────────

	@Test
	void detectsLanguageSharesAboveThreshold() {
		// 7000 Java bytes, 2000 TypeScript bytes, 1000 binary bytes → Java 77.8%, TS 22.2%
		List<FileInfo> files = List.of(source("src/main/Foo.java", "Java", 7_000),
				source("src/ts/index.ts", "TypeScript", 2_000), binary("assets/logo.png", 1_000));

		List<DetectedTechnology> result = detector.detect(ctx(files));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).technology()).isEqualTo("Java");
		assertThat(result.get(0).sharePercent()).isNotNull();
		assertThat(result.get(0).sharePercent().doubleValue()).isGreaterThan(70.0);
		assertThat(result.get(0).confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(result.get(1).technology()).isEqualTo("TypeScript");
	}

	@Test
	void suppressesLanguagesBelowThreshold() {
		// 981 bytes Java, 19 bytes XML → XML is below 2% threshold (1.9%)
		List<FileInfo> files = List.of(source("Main.java", "Java", 981), source("config.xml", "XML", 19));

		List<DetectedTechnology> result = detector.detect(ctx(files));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).technology()).isEqualTo("Java");
	}

	@Test
	void excludesDocsAndBinaryAndSkippedFromCounts() {
		// Java: 500 bytes source; docs: 10000 bytes; binary: 50000 bytes; skipped: 5000 bytes.
		// Only the 500 Java source bytes should count, so Java = 100%.
		List<FileInfo> files = List.of(source("Main.java", "Java", 500), docs("README.md", 10_000),
				binary("image.png", 50_000), skipped("HugeFile.java", 5_000));

		List<DetectedTechnology> result = detector.detect(ctx(files));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).technology()).isEqualTo("Java");
		assertThat(result.get(0).sharePercent().intValue()).isEqualTo(100);
	}

	@Test
	void returnsEmptyListWhenNoExtractedSourceFiles() {
		List<FileInfo> files = List.of(binary("app.exe", 1000), docs("README.md", 500));

		assertThat(detector.detect(ctx(files))).isEmpty();
	}

	@Test
	void returnsEmptyListForEmptyFileList() {
		assertThat(detector.detect(ctx(List.of()))).isEmpty();
	}

	@Test
	void sortsResultsByShareDescending() {
		// TypeScript gets 60%, Java gets 40%.
		List<FileInfo> files = List.of(source("index.ts", "TypeScript", 600),
				source("Main.java", "Java", 400));

		List<DetectedTechnology> result = detector.detect(ctx(files));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).technology()).isEqualTo("TypeScript");
		assertThat(result.get(1).technology()).isEqualTo("Java");
	}

	@Test
	void includesTestSourceFilesInCounts() {
		// Tests are still source code, should be counted.
		List<FileInfo> files = List.of(source("Main.java", "Java", 500),
				test("MainTest.java", "Java", 500));

		List<DetectedTechnology> result = detector.detect(ctx(files));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).technology()).isEqualTo("Java");
		assertThat(result.get(0).sharePercent().intValue()).isEqualTo(100);
	}

	@Test
	void descriptorIsCorrect() {
		assertThat(detector.descriptor().id()).isEqualTo(GenericLanguageDetector.ID);
		assertThat(detector.descriptor().isTechnologyIndependent()).isTrue();
	}

}
