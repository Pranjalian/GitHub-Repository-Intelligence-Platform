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

	/** Block kind of a verbatim file excerpt. */
	public static final String SOURCE = "SOURCE";

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
			// whole key blocks, found on the ORIGINAL lines before anything is withheld
			Set<Integer> keyBlock = privateKeyLines(lines);
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < lines.length; i++) {
				// only file excerpts map text lines to file lines; metadata blocks merely name a location
				Integer fileLine = !SOURCE.equals(block.kind()) || block.startLine() == null ? null : block.startLine() + i;
				String line = lines[i];
				if (line.length() > MAX_LINE) {
					line = TOO_LONG;
					withheld++;
				}
				else if (keyBlock.contains(i) || detected.contains(i + 1) || inRegion(regions, block.filePath(), fileLine)) {
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

	private static final java.util.regex.Pattern KEY_BEGIN = java.util.regex.Pattern
		.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----");

	private static final java.util.regex.Pattern KEY_END = java.util.regex.Pattern
		.compile("-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----");

	/** A line that is only base64 (optionally quoted or concatenated), as in a PEM body. */
	private static final java.util.regex.Pattern BASE64_LINE = java.util.regex.Pattern
		.compile("^[\\s\"'+,]*[A-Za-z0-9+/]{40,}={0,2}(?:\\\\n)?[\\s\"'+,;)]*$");

	private static final java.util.regex.Pattern BASE64_TAIL = java.util.regex.Pattern
		.compile("^[\\s\"'+,]*[A-Za-z0-9+/]{2,}={0,2}(?:\\\\n)?[\\s\"'+,;)]*$");

	/**
	 * 0-based indexes of every line of a private key block: BEGIN through END; from the start of the excerpt
	 * to an END whose BEGIN lies before the excerpt; from a BEGIN to the end of the excerpt when the END lies
	 * after it; and runs of two or more base64-only lines (a body excerpt without either marker). Detected on
	 * the original text, so withholding the marker lines can never expose the body.
	 */
	static Set<Integer> privateKeyLines(String[] lines) {
		Set<Integer> result = new java.util.TreeSet<>();
		int open = -1;
		for (int i = 0; i < lines.length; i++) {
			boolean begin = KEY_BEGIN.matcher(lines[i]).find();
			boolean end = KEY_END.matcher(lines[i]).find();
			if (begin && open < 0) {
				open = i;
			}
			if (end) {
				int from = open >= 0 ? open : 0;
				for (int j = from; j <= i; j++) {
					result.add(j);
				}
				open = -1;
			}
		}
		if (open >= 0) {
			for (int j = open; j < lines.length; j++) {
				result.add(j);
			}
		}
		for (int i = 0; i < lines.length; i++) {
			int run = i;
			while (run < lines.length && BASE64_LINE.matcher(lines[run]).matches()) {
				run++;
			}
			if (run - i >= 2) {
				// a PEM body's last line is usually shorter; it belongs to the run as well
				if (run < lines.length && BASE64_TAIL.matcher(lines[run]).matches()) {
					run++;
				}
				for (int j = i; j < run; j++) {
					result.add(j);
				}
			}
			i = Math.max(i, run);
		}
		return result;
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
