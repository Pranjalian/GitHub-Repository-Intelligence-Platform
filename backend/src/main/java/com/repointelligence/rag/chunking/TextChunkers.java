package com.repointelligence.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural chunkers for configuration and documentation, by plain line rules (nothing is evaluated):
 * YAML by top-level key, properties by the first key segment, Markdown by heading. Comment lines directly
 * above a key or heading belong to the section below them. Small neighbouring config sections are merged
 * (up to {@value #MERGE_LINES} lines), so a file of one-line keys does not become one chunk per line.
 */
final class TextChunkers {

	static final int MERGE_LINES = 30;

	private static final Pattern YAML_KEY = Pattern.compile("^(?:\"([^\"]+)\"|'([^']+)'|([^\\s#\\-:\"'][^:#]*?))\\s*:(?:\\s.*|)$");

	private static final Pattern YAML_DOCUMENT = Pattern.compile("^(?:---|\\.\\.\\.)(?:\\s.*)?$");

	private static final Pattern PROPERTY_KEY = Pattern.compile("^\\s*([^#!\\s=:.]+)[^=:\\s]*\\s*[=:\\s]");

	private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");

	private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");

	private TextChunkers() {
	}

	/** A labelled range of lines. */
	record Section(String label, int start, int end) {
	}

	/** Null when no top-level key was found (e.g. a file that is one list); the caller uses windows then. */
	static List<Section> yaml(String[] lines, int count) {
		List<Integer> starts = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		int document = 1;
		boolean anyKey = false;
		for (int i = 1; i <= count; i++) {
			String line = lines[i - 1];
			int floor = starts.isEmpty() ? 1 : starts.get(starts.size() - 1) + 1;
			if (YAML_DOCUMENT.matcher(line).matches()) {
				if (i > 1) {
					document++;
				}
				starts.add(i);
				labels.add("(document " + document + ")");
				continue;
			}
			Matcher key = YAML_KEY.matcher(line);
			if (key.matches()) {
				String name = key.group(1) != null ? key.group(1) : key.group(2) != null ? key.group(2) : key.group(3).trim();
				starts.add(commentStart(lines, floor, i, "#"));
				labels.add(document > 1 ? name + " (document " + document + ")" : name);
				anyKey = true;
			}
		}
		if (!anyKey) {
			return null;
		}
		List<Section> sections = new ArrayList<>();
		if (starts.get(0) > 1) {
			sections.add(new Section("(document start)", 1, starts.get(0) - 1));
		}
		for (int k = 0; k < starts.size(); k++) {
			int end = k + 1 < starts.size() ? starts.get(k + 1) - 1 : count;
			sections.add(new Section(labels.get(k), starts.get(k), end));
		}
		return merge(clean(sections, lines));
	}

	static List<Section> properties(String[] lines, int count) {
		List<Section> sections = new ArrayList<>();
		String label = null;
		int start = 1;
		for (int i = 1; i <= count; i++) {
			Matcher key = PROPERTY_KEY.matcher(lines[i - 1] + " ");
			String trimmed = lines[i - 1].trim();
			if (trimmed.startsWith("#") || trimmed.startsWith("!") || !key.find()) {
				continue;
			}
			String segment = key.group(1);
			if (segment.equals(label)) {
				continue;
			}
			int sectionStart = commentStart(lines, start, i, "#", "!");
			if (label != null) {
				sections.add(new Section(label, start, sectionStart - 1));
			}
			else if (sectionStart > start) {
				// leading comments of the file belong to the first section
				sectionStart = start;
			}
			label = segment;
			start = sectionStart;
		}
		if (label == null) {
			return null;
		}
		sections.add(new Section(label, start, count));
		return merge(clean(sections, lines));
	}

	static List<Section> markdown(String[] lines, int count) {
		List<Section> sections = new ArrayList<>();
		List<String> path = new ArrayList<>();
		String label = "(introduction)";
		int start = 1;
		boolean inFence = false;
		for (int i = 1; i <= count; i++) {
			String line = lines[i - 1];
			if (FENCE.matcher(line).find()) {
				inFence = !inFence;
				continue;
			}
			Matcher heading = inFence ? null : HEADING.matcher(line);
			if (heading != null && heading.matches()) {
				if (i > start) {
					sections.add(new Section(label, start, i - 1));
				}
				int level = heading.group(1).length();
				while (path.size() >= level) {
					path.remove(path.size() - 1);
				}
				while (path.size() < level - 1) {
					path.add("");
				}
				path.add(heading.group(2));
				label = String.join(" > ", path.stream().filter(p -> !p.isEmpty()).toList());
				start = i;
			}
		}
		if (start <= count) {
			sections.add(new Section(label, start, count));
		}
		return clean(sections, lines);
	}

	/** Start of a run of comment lines directly above line {@code keyLine} (not before {@code floor}). */
	private static int commentStart(String[] lines, int floor, int keyLine, String... prefixes) {
		int start = keyLine;
		while (start - 1 > floor - 1 && start - 1 >= 1 && isComment(lines[start - 2], prefixes)) {
			start--;
		}
		return Math.max(floor, start);
	}

	private static boolean isComment(String line, String... prefixes) {
		String trimmed = line.trim();
		for (String prefix : prefixes) {
			if (trimmed.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** Drops empty and all-blank sections. */
	private static List<Section> clean(List<Section> sections, String[] lines) {
		return sections.stream().filter(s -> s.end() >= s.start() && !LineWindows.blank(lines, s.start(), s.end())).toList();
	}

	/** Merges neighbouring small sections while the merged section stays within {@value #MERGE_LINES} lines. */
	private static List<Section> merge(List<Section> sections) {
		List<Section> merged = new ArrayList<>();
		for (Section section : sections) {
			if (!merged.isEmpty()) {
				Section last = merged.get(merged.size() - 1);
				if (section.end() - last.start() + 1 <= MERGE_LINES && section.start() == last.end() + 1) {
					merged.set(merged.size() - 1, new Section(last.label() + ", " + section.label(), last.start(), section.end()));
					continue;
				}
			}
			merged.add(section);
		}
		return merged;
	}

}
