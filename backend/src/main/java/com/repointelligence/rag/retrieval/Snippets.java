package com.repointelligence.rag.retrieval;

import java.util.List;
import java.util.Locale;

/** A short excerpt of a chunk around the first line that contains a search word. */
public final class Snippets {

	static final int BEFORE = 2;

	static final int LINES = 10;

	private Snippets() {
	}

	public static String around(String content, List<String> words) {
		if (content == null) {
			return null;
		}
		String[] lines = content.split("\n", -1);
		int hit = 0;
		outer: for (int i = 0; i < lines.length; i++) {
			String lower = lines[i].toLowerCase(Locale.ROOT);
			for (String word : words) {
				if (lower.contains(word)) {
					hit = i;
					break outer;
				}
			}
		}
		int from = Math.max(0, hit - BEFORE);
		int to = Math.min(lines.length, from + LINES);
		StringBuilder out = new StringBuilder();
		for (int i = from; i < to; i++) {
			if (i > from) {
				out.append('\n');
			}
			out.append(lines[i].length() > 300 ? lines[i].substring(0, 300) + "..." : lines[i]);
		}
		return out.toString();
	}

}
