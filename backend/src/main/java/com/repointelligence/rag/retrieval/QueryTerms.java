package com.repointelligence.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns user text into search terms. Only identifier-like tokens ({@code [A-Za-z_$][A-Za-z0-9_$]*} and
 * digits) are kept, so the tsquery built here can never contain query syntax from the user. Each token is
 * matched whole or by its camelCase/underscore parts, like the index ({@code code_chunk.tsv}) stores it.
 */
public final class QueryTerms {

	static final int MAX_TERMS = 20;

	private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+");

	private static final Pattern PART = Pattern.compile("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])");

	/** Question words and fillers that carry no meaning for a keyword search. */
	private static final Set<String> STOPWORDS = Set.of("a", "about", "all", "an", "and", "any", "are", "as", "at", "be",
			"by", "can", "code", "could", "do", "does", "explain", "find", "for", "from", "get", "has", "have", "how",
			"i", "if", "in", "into", "is", "it", "its", "me", "of", "on", "or", "please", "show", "so", "tell", "that",
			"the", "their", "them", "then", "there", "these", "this", "those", "to", "used", "uses", "using", "was",
			"we", "what", "when", "where", "which", "who", "why", "with", "work", "works", "you");

	private final List<String> tokens;

	private QueryTerms(List<String> tokens) {
		this.tokens = tokens;
	}

	public static QueryTerms of(String text) {
		Set<String> found = new LinkedHashSet<>();
		Matcher matcher = TOKEN.matcher(text == null ? "" : text);
		while (matcher.find() && found.size() < MAX_TERMS) {
			String token = matcher.group().replace("$", "");
			if (token.length() >= 2 || token.chars().allMatch(Character::isDigit)) {
				found.add(token);
			}
		}
		return new QueryTerms(List.copyOf(found));
	}

	/** Tokens as typed (case kept), in order, without duplicates. */
	public List<String> tokens() {
		return tokens;
	}

	/** Tokens that are not stopwords, for natural-language questions. */
	public List<String> meaningful() {
		return tokens.stream().filter(t -> !STOPWORDS.contains(t.toLowerCase(Locale.ROOT))).toList();
	}

	public boolean isEmpty() {
		return tokens.isEmpty();
	}

	/** Every term must match (search box semantics); null when there is nothing to search for. */
	public String allOf() {
		return join(tokens, " & ");
	}

	/** Any meaningful term may match (question semantics, ranked by how many match); null when none is left. */
	public String anyOf() {
		return join(meaningful(), " | ");
	}

	/** Lower-cased words used to pick a snippet line: every token and its parts. */
	public List<String> highlightWords() {
		Set<String> words = new LinkedHashSet<>();
		for (String token : tokens) {
			words.add(token.toLowerCase(Locale.ROOT));
			words.addAll(parts(token));
		}
		return words.stream().filter(w -> w.length() >= 3 || w.chars().allMatch(Character::isDigit)).toList();
	}

	private static String join(List<String> list, String operator) {
		List<String> expressions = new ArrayList<>();
		for (String token : list) {
			String whole = token.toLowerCase(Locale.ROOT).replace("_", "");
			List<String> parts = parts(token);
			if (parts.size() > 1) {
				expressions.add("(" + whole + " | (" + String.join(" & ", parts) + "))");
			}
			else if (!whole.isEmpty()) {
				String stem = stem(whole);
				expressions.add(stem == null ? whole : "(" + whole + " | " + stem + ":*)");
			}
		}
		return expressions.isEmpty() ? null : String.join(operator, expressions);
	}

	/**
	 * A light, deterministic English stem used as a prefix ({@code stem:*}) next to the word itself, so
	 * "policies" finds "policy", "customers" finds "customer" and "generated" finds "generator". Null when
	 * nothing is stripped or the stem would be shorter than four letters (too unspecific as a prefix). The
	 * index itself is not stemmed (the 'simple' configuration): identifiers must stay exact.
	 */
	static String stem(String word) {
		if (!word.chars().allMatch(Character::isLetter)) {
			return null;
		}
		String stem = word;
		for (String suffix : List.of("ations", "ation", "ings", "ing", "ies", "sses", "ed", "s")) {
			if (word.endsWith(suffix) && !(suffix.equals("s") && word.endsWith("ss"))) {
				// "sses" keeps "ss" (classes -> class)
				stem = word.substring(0, word.length() - suffix.length()) + (suffix.equals("sses") ? "ss" : "");
				break;
			}
		}
		return stem.length() >= 4 && !stem.equals(word) ? stem : null;
	}

	/** camelCase, PascalCase, ACRONYMS and snake_case parts, lower-cased. */
	static List<String> parts(String token) {
		List<String> parts = new ArrayList<>();
		for (String piece : token.split("_")) {
			Matcher matcher = PART.matcher(piece);
			while (matcher.find()) {
				parts.add(matcher.group().toLowerCase(Locale.ROOT));
			}
		}
		return parts;
	}

}
