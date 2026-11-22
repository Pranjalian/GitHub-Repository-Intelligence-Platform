package com.repointelligence.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.analysis.generic.GenericSecretScanAnalyzer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression: a PEM private key must never reach a prompt, whether the excerpt contains the whole block,
 * starts or ends inside it, or holds only body lines. The marker lines used to be withheld one by one and
 * the redactor ran afterwards, so its multi-line PEM pattern no longer matched and body lines leaked.
 */
class PrivateKeyScrubbingTests {

	static final List<String> BODY = List.of("MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun",
			"VTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u+qKhbwKfBstIs+bMY2Zkp18gnTxK",
			"LxoS2tFczGkPLPgizskuemMghRniWaoLcyehkd3qqGElvW/VDL5AaWTg0nLVkjRo",
			"9z+40RQzuVaE8AkAFmxZzow3x+VJYKdjykkJ0iT9wCS0DRTXu269V264Vf/3jvre");

	private final PromptScrubber scrubber = new PromptScrubber(new GenericSecretScanAnalyzer());

	private String sent(List<String> lines) {
		String text = String.join("\n", lines);
		return scrubber.scrub(List.of(new EvidenceBlock("E1", "SOURCE", "src/Keys.java", 1, lines.size(), text, null, false)),
				List.of())
			.blocks()
			.get(0)
			.text();
	}

	private static List<String> keyFile(boolean begin, boolean end) {
		List<String> lines = new ArrayList<>();
		lines.add("class Keys {");
		if (begin) {
			lines.add("-----BEGIN RSA PRIVATE KEY-----");
		}
		lines.addAll(BODY);
		if (end) {
			lines.add("-----END RSA PRIVATE KEY-----");
		}
		lines.add("}");
		return lines;
	}

	private static void assertNoKeyMaterial(String sent) {
		for (String body : BODY) {
			assertThat(sent).doesNotContain(body).doesNotContain(body.substring(0, 20));
		}
		assertThat(sent).doesNotContain("PRIVATE KEY").startsWith("class Keys {").endsWith("}");
	}

	@Test
	void aFullPemBlockIsWithheldIncludingItsBody() {
		assertNoKeyMaterial(sent(keyFile(true, true)));
	}

	@Test
	void anExcerptStartingMidBlockWithholdsEverythingUpToTheEndMarker() {
		String sent = sent(keyFile(false, true));

		// where the key starts is unknown, so the excerpt is withheld from its first line to the END marker
		for (String body : BODY) {
			assertThat(sent).doesNotContain(body.substring(0, 20));
		}
		assertThat(sent).doesNotContain("PRIVATE KEY").doesNotContain("class Keys").endsWith("}");
	}

	@Test
	void theShortLastLineOfABodyOnlyExcerptIsWithheldToo() {
		List<String> lines = new ArrayList<>(BODY);
		lines.add("Vf/3jvreQ==");
		lines.add("int after = 1;");

		String sent = sent(lines);

		assertThat(sent).doesNotContain("Vf/3jvreQ==").contains("int after = 1;");
	}

	@Test
	void anExcerptEndingMidBlockWithholdsEverythingAfterTheBeginMarker() {
		List<String> lines = List.of("class Keys {", "-----BEGIN PRIVATE KEY-----", BODY.get(0), BODY.get(1));

		String sent = sent(lines);

		assertThat(sent).startsWith("class Keys {").doesNotContain("PRIVATE KEY");
		assertThat(sent).doesNotContain(BODY.get(0)).doesNotContain(BODY.get(1));
	}

	@Test
	void aBodyOnlyExcerptWithoutEitherMarkerIsWithheld() {
		assertNoKeyMaterial(sent(keyFile(false, false)));
	}

	@Test
	void aKeyBuiltByJavaStringConcatenationIsWithheld() {
		List<String> lines = List.of("String key = \"-----BEGIN PRIVATE KEY-----\\n\"", "    + \"" + BODY.get(0) + "\\n\"",
				"    + \"" + BODY.get(1) + "\\n\"", "    + \"-----END PRIVATE KEY-----\";", "int after = 1;");

		String sent = sent(lines);

		assertThat(sent).doesNotContain(BODY.get(0)).doesNotContain(BODY.get(1)).contains("int after = 1;");
	}

	@Test
	void tabIndentedQuotedBodyLinesAreWithheld() {
		List<String> lines = List.of("\t\t\"" + BODY.get(0) + "\\n\" +", "\t\t\"" + BODY.get(1) + "\\n\" +",
				"\t\t\"Vf/3jvre\\n\";", "int after = 1;");

		String sent = sent(lines);

		assertThat(sent).doesNotContain(BODY.get(0)).doesNotContain(BODY.get(1)).doesNotContain("Vf/3jvre")
			.contains("int after = 1;");
	}

	@Test
	void ordinaryCodeIsNotMistakenForKeyMaterial() {
		List<String> lines = List.of("class A {", "  String url = \"https://example.org/a/b\";",
				"  int computeTheTotalAmountOfAllOrdersInTheShopForToday = 0;", "}");

		assertThat(sent(lines)).isEqualTo(String.join("\n", lines));
	}

	@Test
	void aStoredPrivateKeyFindingCoversTheBodyAfterItsMarkerLine() {
		assertThat(AIGateway.PRIVATE_KEY_REGION_LINES).isGreaterThanOrEqualTo(100);
		SecretRegion region = new SecretRegion("src/Keys.java", 2, 2 + AIGateway.PRIVATE_KEY_REGION_LINES);
		String text = String.join("\n", BODY.get(0), "int notBase64ButInsideTheReportedKeyRegion = 1;");

		String sent = scrubber.scrub(List.of(new EvidenceBlock("E1", "SOURCE", "src/Keys.java", 3, 4, text, null, false)),
				List.of(region))
			.blocks()
			.get(0)
			.text();

		assertThat(sent).doesNotContain(BODY.get(0)).doesNotContain("notBase64");
	}

}
