package com.repointelligence.repository.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.repository.domain.FileType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileClassifierTests {

	private final FileClassifier classifier = new FileClassifier();

	@ParameterizedTest
	@CsvSource({ "src/main/java/com/acme/App.java,Java", "frontend/src/App.tsx,TypeScript", "index.js,JavaScript",
			"scripts/deploy.py,Python", "cmd/server/main.go,Go", "src/Program.cs,C#", "Dockerfile,Dockerfile",
			"docs/README.md,Markdown", "src/main/resources/application.yml,YAML", "package.json,JSON", "pom.xml,XML",
			"build.gradle,Groovy", "Jenkinsfile,Groovy", "UPPER/APP.JAVA,Java" })
	void detectsLanguages(String path, String language) {
		assertThat(classifier.languageOf(path)).isEqualTo(language);
	}

	@ParameterizedTest
	@CsvSource({ "LICENSE", "logo.png", ".gitignore", "noextension", "trailingdot.", "data.bin" })
	void unknownFilesHaveNoLanguage(String path) {
		assertThat(classifier.languageOf(path)).isNull();
	}

	@ParameterizedTest
	@CsvSource({
			// source
			"src/main/java/com/acme/App.java,SOURCE", "main.go,SOURCE", "src/Program.cs,SOURCE", "app/index.html,SOURCE",
			// tests
			"src/test/java/com/acme/AppTest.java,TEST", "modules/api/src/test/java/X.java,TEST",
			"frontend/src/App.test.tsx,TEST", "src/__tests__/helpers.ts,TEST", "tests/test_app.py,TEST",
			"service/app_test.go,TEST", "src/test/resources/fixture.json,TEST", "web/button.spec.ts,TEST",
			// build manifests
			"pom.xml,BUILD", "modules/api/pom.xml,BUILD", "package.json,BUILD", "build.gradle.kts,BUILD",
			"requirements.txt,BUILD", "go.mod,BUILD", "App/App.csproj,BUILD", "Makefile,BUILD", "pnpm-lock.yaml,BUILD",
			// resources
			"src/main/resources/application.yml,RESOURCE", "src/main/resources/db/migration/V1__x.sql,RESOURCE",
			// config
			"config/app.yml,CONFIG", "settings.json,CONFIG", ".env.example,CONFIG", ".gitignore,CONFIG",
			"deploy/values.toml,CONFIG",
			// docs
			"README.md,DOCS", "docs/guide.png,DOCS", "docs/architecture/overview.html,DOCS", "LICENSE,DOCS", "notes.txt,DOCS",
			"CHANGELOG.rst,DOCS",
			// other
			"logo.png,OTHER", "data.bin,OTHER", "fonts/a.woff2,OTHER" })
	void classifiesFileTypes(String path, FileType expected) {
		assertThat(classifier.typeOf(path)).as(path).isEqualTo(expected);
	}

}
