package com.repointelligence.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Content lines with the file line each one came from, so a chunk can be split anywhere and still report
 * its exact line range (a marker line that stands for elided lines carries the first elided line).
 */
final class Piece {

	final List<Integer> lineNumbers = new ArrayList<>();

	final List<String> texts = new ArrayList<>();

	void add(int lineNumber, String text) {
		lineNumbers.add(lineNumber);
		texts.add(text);
	}

	int size() {
		return texts.size();
	}

	boolean isBlank() {
		return texts.stream().allMatch(String::isBlank);
	}

	/** Splits into parts of at most {@code max} lines (no overlap). */
	List<Piece> split(int max) {
		List<Piece> parts = new ArrayList<>();
		for (int from = 0; from < size(); from += max) {
			Piece part = new Piece();
			for (int i = from; i < Math.min(size(), from + max); i++) {
				part.add(lineNumbers.get(i), texts.get(i));
			}
			parts.add(part);
		}
		return parts;
	}

	int firstLine() {
		return lineNumbers.get(0);
	}

	int lastLine() {
		return lineNumbers.get(lineNumbers.size() - 1);
	}

	String content() {
		return String.join("\n", texts);
	}

	static Piece ofLines(String[] lines, int from, int to) {
		Piece piece = new Piece();
		for (int i = from; i <= to; i++) {
			piece.add(i, lines[i - 1]);
		}
		return piece;
	}

}
