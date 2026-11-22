package com.repointelligence.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.analysis.generic.GenericSecretScanAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three scrubbing layers, without a database. */
class PromptScrubberTests {

	private final PromptScrubber scrubber = new PromptScrubber(new GenericSecretScanAnalyzer());

	private static EvidenceBlock source(int start, String text) {
		return new EvidenceBlock("E1", "SOURCE", "src/App.java", start, start + text.split("\n").length - 1, text, null, false);
	}

	@Test
	void linesInsideAStoredSecretRegionAreWithheld() {
		String text = String.join("\n", "class App {", "  String a = \"opaque-value-1\";", "  String b = \"opaque-value-2\";", "}");

		PromptScrubber.Scrubbed result = scrubber.scrub(List.of(source(10, text)),
				List.of(new SecretRegion("src/App.java", 12, 12)));

		assertThat(result.blocks().get(0).text()).contains("opaque-value-1")
			.doesNotContain("opaque-value-2")
			.contains(PromptScrubber.WITHHELD);
		assertThat(result.withheldLines()).isEqualTo(1);
	}

	@Test
	void secretRulesCatchSecretsThatHaveNoStoredFinding() {
		String text = String.join("\n", "class App {", "  String key = \"AKIA4HVZ7QK2M9TXR3WB\";",
				"  String pem = \"-----BEGIN RSA PRIVATE KEY-----\";", "}");

		PromptScrubber.Scrubbed result = scrubber.scrub(List.of(source(1, text)), List.of());

		assertThat(result.blocks().get(0).text()).doesNotContain("AKIA4HVZ7QK2M9TXR3WB").doesNotContain("BEGIN RSA PRIVATE KEY");
		assertThat(result.withheldLines()).isEqualTo(2);
	}

	@Test
	void theRedactorMasksWhatTheRulesSkip() {
		String text = "url=https://x?token=abcdef1234567890 Authorization: Bearer abc.def.ghi123456";

		String scrubbed = scrubber.scrubText(text);

		assertThat(scrubbed).doesNotContain("abcdef1234567890").doesNotContain("abc.def.ghi123456");
	}

	@Test
	void regionsOnlyApplyToSourceBlocksOfTheSameFile() {
		EvidenceBlock metadata = new EvidenceBlock("E1", "TARGET", "src/App.java", 12, 12, "class App\nfile: src/App.java",
				null, false);
		EvidenceBlock otherFile = new EvidenceBlock("E2", "SOURCE", "src/Other.java", 12, 12, "int x = 1;", null, false);

		PromptScrubber.Scrubbed result = scrubber.scrub(List.of(metadata, otherFile),
				List.of(new SecretRegion("src/App.java", 12, 13)));

		assertThat(result.withheldLines()).isZero();
	}

	@Test
	void overLongLinesAreWithheldBecauseTheScannerDoesNotInspectThem() {
		PromptScrubber.Scrubbed result = scrubber.scrub(List.of(source(1, "x".repeat(2500))), List.of());

		assertThat(result.blocks().get(0).text()).isEqualTo(PromptScrubber.TOO_LONG);
	}

}
