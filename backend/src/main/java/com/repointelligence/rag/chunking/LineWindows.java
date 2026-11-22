package com.repointelligence.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/** Line helpers shared by the chunkers. */
final class LineWindows {

	private LineWindows() {
	}

	static String[] lines(String text) {
		return text.split("\r?\n", -1);
	}

	/** Lines {@code from..to} (1-based, inclusive). */
	static String join(String[] lines, int from, int to) {
		StringBuilder out = new StringBuilder();
		for (int i = from; i <= to; i++) {
			if (i > from) {
				out.append('\n');
			}
			out.append(lines[i - 1]);
		}
		return out.toString();
	}

	static boolean blank(String[] lines, int from, int to) {
		for (int i = from; i <= to; i++) {
			if (!lines[i - 1].isBlank()) {
				return false;
			}
		}
		return true;
	}

	/** Number of lines, not counting one trailing empty line after a final newline. */
	static int count(String[] lines) {
		int n = lines.length;
		return n > 0 && lines[n - 1].isEmpty() ? n - 1 : n;
	}

	/** Fixed windows of {@code size} lines that overlap by {@code overlap}, skipping all-blank windows. */
	static List<int[]> windows(int lineCount, int size, int overlap) {
		List<int[]> result = new ArrayList<>();
		if (lineCount <= 0) {
			return result;
		}
		int step = Math.max(1, size - overlap);
		for (int start = 1; start <= lineCount; start += step) {
			int end = Math.min(lineCount, start + size - 1);
			result.add(new int[] { start, end });
			if (end == lineCount) {
				break;
			}
		}
		return result;
	}

}
