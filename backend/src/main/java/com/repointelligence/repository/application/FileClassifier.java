package com.repointelligence.repository.application;

import com.repointelligence.repository.domain.FileType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * Classifies files by language and role. Everything here is table data (extension and file-name
 * maps, ordered path rules); adding a language or convention means adding a row, not a branch.
 */
@Component
public class FileClassifier {

	/** Lower-case extension (without the dot) to language. */
	private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
			Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"),
			Map.entry("scala", "Scala"), Map.entry("groovy", "Groovy"), Map.entry("gradle", "Groovy"),
			Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("mts", "TypeScript"),
			Map.entry("cts", "TypeScript"), Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"),
			Map.entry("mjs", "JavaScript"), Map.entry("cjs", "JavaScript"), Map.entry("py", "Python"),
			Map.entry("go", "Go"), Map.entry("cs", "C#"), Map.entry("rs", "Rust"), Map.entry("rb", "Ruby"),
			Map.entry("php", "PHP"), Map.entry("swift", "Swift"), Map.entry("c", "C"), Map.entry("h", "C"),
			Map.entry("cpp", "C++"), Map.entry("cc", "C++"), Map.entry("cxx", "C++"), Map.entry("hpp", "C++"),
			Map.entry("hh", "C++"), Map.entry("sh", "Shell"), Map.entry("bash", "Shell"), Map.entry("ps1", "PowerShell"),
			Map.entry("sql", "SQL"), Map.entry("html", "HTML"), Map.entry("htm", "HTML"), Map.entry("css", "CSS"),
			Map.entry("scss", "SCSS"), Map.entry("less", "Less"), Map.entry("vue", "Vue"), Map.entry("svelte", "Svelte"),
			Map.entry("dart", "Dart"), Map.entry("lua", "Lua"), Map.entry("r", "R"), Map.entry("pl", "Perl"),
			Map.entry("ex", "Elixir"), Map.entry("exs", "Elixir"), Map.entry("hs", "Haskell"),
			Map.entry("clj", "Clojure"), Map.entry("tf", "Terraform"),
			// data formats and documentation
			Map.entry("json", "JSON"), Map.entry("yml", "YAML"), Map.entry("yaml", "YAML"), Map.entry("xml", "XML"),
			Map.entry("toml", "TOML"), Map.entry("properties", "Properties"), Map.entry("ini", "INI"),
			Map.entry("cfg", "INI"), Map.entry("conf", "INI"), Map.entry("md", "Markdown"),
			Map.entry("markdown", "Markdown"), Map.entry("rst", "reStructuredText"), Map.entry("adoc", "AsciiDoc"));

	/** Well-known file names without a useful extension. */
	private static final Map<String, String> BY_FILE_NAME = Map.of("Dockerfile", "Dockerfile", "Makefile", "Makefile",
			"Jenkinsfile", "Groovy", "Gemfile", "Ruby", "Rakefile", "Ruby", "Vagrantfile", "Ruby");

	/** Languages that describe data or documentation, not source code. */
	private static final Set<String> DATA_LANGUAGES = Set.of("JSON", "YAML", "XML", "TOML", "Properties", "INI");

	private static final Set<String> DOC_LANGUAGES = Set.of("Markdown", "reStructuredText", "AsciiDoc");

	private static final Set<String> BUILD_FILE_NAMES = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
			"settings.gradle", "settings.gradle.kts", "gradle.properties", "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat",
			"package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "pnpm-workspace.yaml", "tsconfig.json",
			"go.mod", "go.sum", "Cargo.toml", "Cargo.lock", "Gemfile", "Gemfile.lock", "requirements.txt",
			"pyproject.toml", "setup.py", "setup.cfg", "Pipfile", "Makefile", "CMakeLists.txt", "Dockerfile");

	private static final Set<String> BUILD_EXTENSIONS = Set.of("csproj", "sln", "vbproj", "fsproj");

	private static final Set<String> DOC_FILE_NAMES = Set.of("LICENSE", "LICENCE", "NOTICE", "README", "CHANGELOG",
			"CONTRIBUTING", "AUTHORS", "CODEOWNERS");

	private static final List<String> TEST_PATTERNS = List.of("**/src/test/**", "src/test/**", "**/__tests__/**",
			"__tests__/**", "**/*.test.*", "**/*.spec.*", "**/test_*.py", "**/*_test.py", "**/*_test.go",
			"**/tests/**", "tests/**");

	private static final List<String> RESOURCE_PATTERNS = List.of("**/src/main/resources/**", "src/main/resources/**");

	private static final List<String> DOC_PATTERNS = List.of("**/docs/**", "docs/**");

	private final AntPathMatcher matcher = new AntPathMatcher("/");

	/** The language of a file, or {@code null} when it is not recognised. */
	public String languageOf(String path) {
		String name = fileName(path);
		String byName = BY_FILE_NAME.get(name);
		if (byName != null) {
			return byName;
		}
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return null;
		}
		return BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * The role of a file. Rules are ordered: tests, build manifests, documentation, resources, then
	 * language-based source/config, and finally {@link FileType#OTHER}.
	 */
	public FileType typeOf(String path) {
		String name = fileName(path);
		if (matchesAny(TEST_PATTERNS, path)) {
			return FileType.TEST;
		}
		if (BUILD_FILE_NAMES.contains(name) || BUILD_EXTENSIONS.contains(extensionOf(name))) {
			return FileType.BUILD;
		}
		String language = languageOf(path);
		if (matchesAny(DOC_PATTERNS, path) || DOC_FILE_NAMES.contains(stripExtension(name))
				|| (language != null && DOC_LANGUAGES.contains(language)) || "txt".equals(extensionOf(name))) {
			return FileType.DOCS;
		}
		if (matchesAny(RESOURCE_PATTERNS, path)) {
			return FileType.RESOURCE;
		}
		if (language != null && DATA_LANGUAGES.contains(language) || name.startsWith(".env")
				|| name.equals(".editorconfig") || name.equals(".gitignore") || name.equals(".gitattributes")) {
			return FileType.CONFIG;
		}
		return language != null ? FileType.SOURCE : FileType.OTHER;
	}

	private boolean matchesAny(List<String> patterns, String path) {
		for (String pattern : patterns) {
			if (matcher.match(pattern, path)) {
				return true;
			}
		}
		return false;
	}

	private static String fileName(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String stripExtension(String name) {
		int dot = name.indexOf('.');
		return dot <= 0 ? name : name.substring(0, dot);
	}

}
