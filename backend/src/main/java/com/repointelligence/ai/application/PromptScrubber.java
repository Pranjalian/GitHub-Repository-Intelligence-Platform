package com.repointelligence.ai.application;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.analysis.generic.GenericSecretScanAnalyzer;
import com.repointelligence.common.security.SecretRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps secrets out of prompts, in three layers, before anything is hashed, cached or sent:
 * <ol>
 * <li>lines inside a region where a scanner reported a possible secret (stored findings) are withheld;</li>
 * <li>lines on which a secret rule matches now (the same rules as the secret scanner) are withheld, so a
 * secret is caught even where no finding was stored;</li>
 * <li>{@link SecretRedactor} masks key=value secrets, tokens and keys in everything that remains.</li>
 * </ol>
 * Over-long lines (which the scanner does not inspect) are withheld as well.
 */
public final class PromptScrubber {

	public static final String WITHHELD = "[line withheld: a possible secret was detected here]";

	static final String TOO_LONG = "[line withheld: longer than 2000 characters]";

	private static final int MAX_LINE = 2000;

	private final GenericSecretScanAnalyzer scanner;

	public PromptScrubber(GenericSecretScanAnalyzer scanner) {
		this.scanner = scanner;
	}

	/** What was sent and how many lines were withheld. */
	public record Scrubbed(List<EvidenceBlock> blocks, int withheldLines) {
	}

	public Scrubbed scrub(List<EvidenceBlock> blocks, List<SecretRegion> regions) {
		List<EvidenceBlock> result = new ArrayList<>();
		int withheld = 0;
		for (EvidenceBlock block : blocks) {
			String text = block.text() == null ? "" : block.text();
			String[] lines = text.split("\r?\n", -1);
			Set<Integer> detected = scanner.secretLines(block.filePath(), text);
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < lines.length; i++) {
				Integer fileLine = block.startLine() == null ? null : block.startLine() + i;
				String line = lines[i];
				if (line.length() > MAX_LINE) {
					line = TOO_LONG;
					withheld++;
				}
				else if (detected.contains(i + 1) || inRegion(regions, block.filePath(), fileLine)) {
					line = WITHHELD;
					withheld++;
				}
				if (i > 0) {
					out.append('\n');
				}
				out.append(line);
			}
			result.add(block.withText(SecretRedactor.redact(out.toString()), block.endLine(), false));
		}
		return new Scrubbed(result, withheld);
	}

	/** Redacts a single piece of repository-derived text that is not a file excerpt. */
	public String scrubText(String text) {
		if (text == null) {
			return null;
		}
		return scrub(List.of(new EvidenceBlock("x", "TEXT", null, null, null, text, null, false)), List.of()).blocks()
			.get(0)
			.text();
	}

	private static boolean inRegion(List<SecretRegion> regions, String path, Integer line) {
		if (path == null || line == null) {
			return false;
		}
		for (SecretRegion region : regions) {
			if (region.covers(path, line)) {
				return true;
			}
		}
		return false;
	}

}
