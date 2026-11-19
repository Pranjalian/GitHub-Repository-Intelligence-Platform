package com.repointelligence.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.common.exception.InvalidRepositoryUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubUrlParserTests {

	private final GitHubUrlParser parser = new GitHubUrlParser();

	private static final GitHubRepositoryRef SPRING_PETCLINIC = new GitHubRepositoryRef("spring-projects", "spring-petclinic", null);

	@ParameterizedTest
	@ValueSource(strings = { "https://github.com/spring-projects/spring-petclinic",
			"https://github.com/spring-projects/spring-petclinic/", "https://github.com/spring-projects/spring-petclinic.git",
			"https://www.github.com/spring-projects/spring-petclinic", "http://github.com/spring-projects/spring-petclinic",
			"github.com/spring-projects/spring-petclinic", "www.github.com/spring-projects/spring-petclinic",
			"  https://GitHub.com/spring-projects/spring-petclinic  ", "https://github.com/spring-projects/spring-petclinic#readme" })
	void supportedFormatsNormalizeToTheSameRef(String url) {
		assertThat(parser.parse(url)).isEqualTo(SPRING_PETCLINIC);
	}

	@Test
	void treeSuffixRecordsBranch() {
		assertThat(parser.parse("https://github.com/o/r/tree/main")).isEqualTo(new GitHubRepositoryRef("o", "r", "main"));
		assertThat(parser.parse("https://github.com/o/r/tree/feature/login-page"))
			.isEqualTo(new GitHubRepositoryRef("o", "r", "feature/login-page"));
	}

	@Test
	void canonicalUrlIsBuiltFromTheRef() {
		assertThat(parser.parse("github.com/o/r.git").canonicalUrl()).isEqualTo("https://github.com/o/r");
	}

	@Test
	void dotsAreAllowedInRepoNames() {
		assertThat(parser.parse("https://github.com/o/my.repo_v2-x").name()).isEqualTo("my.repo_v2-x");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "https://gitlab.com/o/r", "https://github.com.evil.com/o/r", "https://evil.com/github.com/o/r",
			"https://notgithub.com/o/r", "https://user@github.com/o/r", "https://user:pw@github.com/o/r",
			"user@github.com/o/r", "https://github.com:8443/o/r", "github.com:80/o/r", "file:///etc/passwd",
			"ftp://github.com/o/r", "javascript:alert(1)", "ssh://git@github.com/o/r", "https://github.com/o",
			"https://github.com/", "https://github.com", "https://github.com/../r", "https://github.com/o/..",
			"https://github.com/o/.", "https://github.com/-bad/r", "https://github.com/o_o/r", "https://github.com/o/r r",
			"https://github.com/o/r?ref=x", "https://github.com/o/r/blob/main/README.md", "https://github.com/o/r/issues",
			"https://github.com/o/r/tree", "https://github.com/o/r/tree/../../x", "https://github.com/o/r/tree/a/../b",
			"https://github.com/o/r/tree/ma%2Fin%00", "https://github.com\\@evil.com/o/r", "https://github.com/o/.git",
			"https://github.com/%2e%2e/r", "https://github.com/o/r%00" })
	void unsafeOrUnsupportedInputIsRejected(String url) {
		assertThatThrownBy(() -> parser.parse(url)).isInstanceOf(InvalidRepositoryUrlException.class);
	}

	@Test
	void ownerLongerThan39CharactersIsRejected() {
		String owner = "a".repeat(40);
		assertThatThrownBy(() -> parser.parse("https://github.com/" + owner + "/r"))
			.isInstanceOf(InvalidRepositoryUrlException.class);
		assertThat(parser.parse("https://github.com/" + "a".repeat(39) + "/r").owner()).hasSize(39);
	}

	@Test
	void overlongInputIsRejected() {
		assertThatThrownBy(() -> parser.parse("https://github.com/o/" + "r".repeat(600)))
			.isInstanceOf(InvalidRepositoryUrlException.class);
	}

}
