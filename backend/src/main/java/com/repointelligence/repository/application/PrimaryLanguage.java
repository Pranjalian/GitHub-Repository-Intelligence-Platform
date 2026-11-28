package com.repointelligence.repository.application;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Picks the "primary language" of a snapshot from its detected languages.
 *
 * <p>The language detector measures bytes per file extension, so stylesheets, markup and data files compete with
 * real source code (spring-petclinic: CSS 54.4%, Java 27.5%). A stylesheet or a JSON file is not what people mean by
 * the primary language, so those are skipped here. Reported shares are not changed. When only such languages exist
 * (a pure documentation or static-site repository), the largest of them is used rather than none.
 *
 * <p>The names are the ones {@link FileClassifier} assigns. The UI mirrors this list in
 * {@code frontend/src/lib/primaryLanguage.ts}; keep the two in step.
 */
final class PrimaryLanguage {

	/** Styling, markup, data and prose: never chosen as the primary language while a programming language exists. */
	static final Set<String> NOT_PROGRAMMING = Set.of("CSS", "SCSS", "Less", "HTML", "JSON", "YAML", "XML", "TOML",
			"Properties", "INI", "Markdown", "reStructuredText", "AsciiDoc");

	private PrimaryLanguage() {
	}

	static <T> Optional<T> choose(List<T> languages, Function<T, String> name, Function<T, BigDecimal> share) {
		Comparator<T> byShare = Comparator
			.comparing((T t) -> share.apply(t) == null ? BigDecimal.ZERO : share.apply(t))
			.thenComparing(name, Comparator.reverseOrder());
		Optional<T> programming = languages.stream()
			.filter(t -> !NOT_PROGRAMMING.contains(name.apply(t)))
			.max(byShare);
		return programming.isPresent() ? programming : languages.stream().max(byShare);
	}

}
