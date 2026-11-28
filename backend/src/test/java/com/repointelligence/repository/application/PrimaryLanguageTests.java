package com.repointelligence.repository.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimaryLanguageTests {

	private record Lang(String name, BigDecimal share) {
	}

	private static Lang lang(String name, String share) {
		return new Lang(name, share == null ? null : new BigDecimal(share));
	}

	private static String primary(List<Lang> languages) {
		return PrimaryLanguage.choose(languages, Lang::name, Lang::share).map(Lang::name).orElse(null);
	}

	@Test
	void skipsStylesheetsAndMarkupEvenWhenTheyHaveTheLargestShare() {
		// spring-petclinic as measured: CSS 54.4%, Java 27.5%
		assertThat(primary(List.of(lang("CSS", "54.4"), lang("Java", "27.5"), lang("HTML", "6.0")))).isEqualTo("Java");
	}

	@Test
	void skipsDataFormats() {
		assertThat(primary(List.of(lang("JSON", "60.0"), lang("YAML", "10.0"), lang("TypeScript", "30.0"))))
			.isEqualTo("TypeScript");
	}

	@Test
	void picksTheLargestProgrammingLanguage() {
		assertThat(primary(List.of(lang("Python", "40.0"), lang("Shell", "5.0"), lang("CSS", "55.0")))).isEqualTo("Python");
	}

	@Test
	void aLanguageWithoutAShareCountsAsZero() {
		assertThat(primary(List.of(lang("Java", null), lang("Shell", "0.5")))).isEqualTo("Shell");
		assertThat(primary(List.of(lang("Java", null), lang("CSS", "90.0")))).isEqualTo("Java");
	}

	@Test
	void fallsBackToTheLargestNonProgrammingLanguageWhenNothingElseExists() {
		assertThat(primary(List.of(lang("Markdown", "30.0"), lang("CSS", "70.0")))).isEqualTo("CSS");
	}

	@Test
	void tiesBreakAlphabeticallyAndAnEmptyListHasNoPrimary() {
		assertThat(primary(List.of(lang("Go", "50.0"), lang("C", "50.0")))).isEqualTo("C");
		assertThat(primary(List.of())).isNull();
	}

}
