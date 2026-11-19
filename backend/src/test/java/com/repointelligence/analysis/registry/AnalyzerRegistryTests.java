package com.repointelligence.analysis.registry;

import static com.repointelligence.support.StubAnalyzers.analyzer;
import static com.repointelligence.support.StubAnalyzers.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.analysis.application.AnalyzerSetHashProvider;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.CodeAnalyzer;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.support.StubAnalyzers.StubAnalyzer;
import com.repointelligence.support.StubAnalyzers.StubDetector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnalyzerRegistryTests {

	private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private static StubAnalyzer code(String id) {
		return analyzer(id, AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC).languages("Java");
	}

	private static AnalyzerRegistry registry(List<Analyzer> analyzers) {
		return new AnalyzerRegistry(List.of(), analyzers, AnalyzerSettings.defaults());
	}

	private static AnalyzerSettings disabled(String... ids) {
		Map<String, AnalyzerSettings.AnalyzerConfig> configs = new HashMap<>();
		for (String id : ids) {
			configs.put(id, new AnalyzerSettings.AnalyzerConfig(false, Map.of()));
		}
		return new AnalyzerSettings(configs);
	}

	// ---- discovery -----------------------------------------------------------------------------

	@Test
	void discoversAnalyzerAndDetectorBeansFromTheSpringContext() {
		new ApplicationContextRunner().withBean(AnalyzerSettings.class, AnalyzerSettings::defaults)
			.withBean("pmd", Analyzer.class, () -> code("pmd-java"))
			.withBean("secrets", Analyzer.class,
					() -> analyzer("secrets", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC))
			.withBean("manifest", TechnologyDetector.class, () -> new StubDetector("manifest-detector", "1.0.0"))
			.withBean(AnalyzerRegistry.class)
			.run(context -> {
				AnalyzerRegistry registry = context.getBean(AnalyzerRegistry.class);
				assertThat(registry.analyzers()).extracting(a -> a.descriptor().id())
					.containsExactly("pmd-java", "secrets");
				assertThat(registry.detectors()).extracting(d -> d.descriptor().id())
					.containsExactly("manifest-detector");
				assertThat(context.getBean(AnalyzerSetHashProvider.class).currentHash())
					.isEqualTo(registry.analyzerSetHash());
			});
	}

	@Test
	void startsWithNoAnalyzersAtAll() {
		new ApplicationContextRunner().withBean(AnalyzerSettings.class, AnalyzerSettings::defaults)
			.withBean(AnalyzerRegistry.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AnalyzerRegistry.class).analyzers()).isEmpty();
			});
	}

	@Test
	void groupsAnalyzersByCategoryAndLooksThemUpById() {
		AnalyzerRegistry registry = registry(List.of(code("b-code"), code("a-code"),
				analyzer("secrets", AnalysisCategory.SECURITY, CapabilityLevel.GENERIC)));

		assertThat(registry.byCategory().get(AnalysisCategory.CODE_QUALITY)).extracting(a -> a.descriptor().id())
			.containsExactly("a-code", "b-code");
		assertThat(registry.byCategory()).containsOnlyKeys(AnalysisCategory.CODE_QUALITY, AnalysisCategory.SECURITY);
		assertThat(registry.analyzer("secrets").descriptor().category()).isEqualTo(AnalysisCategory.SECURITY);
		assertThatThrownBy(() -> registry.analyzer("nope")).isInstanceOf(IllegalArgumentException.class);
	}

	// ---- analyzer-set hash ---------------------------------------------------------------------

	@Test
	void hashOfAnEmptyRegistryIsTheHashOfTheEmptyString() {
		assertThat(registry(List.of()).analyzerSetHash()).isEqualTo(EMPTY_SHA256);
	}

	@Test
	void hashIsStableHexAndIndependentOfRegistrationOrder() {
		String first = registry(List.of(code("a"), code("b"))).analyzerSetHash();
		String second = registry(List.of(code("b"), code("a"))).analyzerSetHash();

		assertThat(first).isEqualTo(second).matches("^[0-9a-f]{64}$").isNotEqualTo(EMPTY_SHA256);
	}

	@Test
	void hashChangesWhenAnAnalyzerVersionChanges() {
		StubAnalyzer pmd = code("pmd-java");

		String before = registry(List.of(pmd)).analyzerSetHash();
		String after = registry(List.of(pmd.version("1.1.0"))).analyzerSetHash();

		assertThat(after).isNotEqualTo(before);
	}

	@Test
	void hashChangesWhenAnAnalyzerIsAddedOrRemoved() {
		String one = registry(List.of(code("a"))).analyzerSetHash();
		String two = registry(List.of(code("a"), code("b"))).analyzerSetHash();

		assertThat(two).isNotEqualTo(one);
	}

	@Test
	void hashIncludesDetectorsAndIgnoresDisabledAnalyzers() {
		AnalyzerRegistry plain = new AnalyzerRegistry(List.of(), List.of(code("a")), AnalyzerSettings.defaults());
		AnalyzerRegistry withDetector = new AnalyzerRegistry(List.of(new StubDetector("d", "1")), List.of(code("a")),
				AnalyzerSettings.defaults());
		AnalyzerRegistry withDisabled = new AnalyzerRegistry(List.of(), List.of(code("a"), code("b")), disabled("b"));

		assertThat(withDetector.analyzerSetHash()).isNotEqualTo(plain.analyzerSetHash());
		assertThat(withDisabled.analyzerSetHash()).isEqualTo(plain.analyzerSetHash());
	}

	// ---- startup validation --------------------------------------------------------------------

	@Test
	void duplicateIdsAreRejected() {
		assertThatThrownBy(() -> registry(List.of(code("same"), code("same"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Duplicate analyzer id: same");
		assertThatThrownBy(() -> new AnalyzerRegistry(List.of(new StubDetector("same", "1")), List.of(code("same")),
				AnalyzerSettings.defaults()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void analyzersMustNotClaimTheTechnologyCategory() {
		assertThatThrownBy(
				() -> registry(List.of(analyzer("bad", AnalysisCategory.TECHNOLOGY, CapabilityLevel.GENERIC))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("implement TechnologyDetector");
	}

	@Test
	void detectorsMustUseTheTechnologyCategory() {
		TechnologyDetector wrong = new TechnologyDetector() {
			@Override
			public AnalyzerDescriptor descriptor() {
				return AnalyzerDescriptor
					.builder("wrong", "wrong", "1", AnalysisCategory.SECURITY, CapabilityLevel.BASIC)
					.build();
			}

			@Override
			public List<DetectedTechnology> detect(AnalysisContext context) {
				return List.of();
			}
		};

		assertThatThrownBy(() -> new AnalyzerRegistry(List.of(wrong), List.of(), AnalyzerSettings.defaults()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("must have category TECHNOLOGY");
	}

	@Test
	void anAnalyzerInterfaceMustMatchTheDeclaredCategory() {
		CodeAnalyzer mismatched = new CodeAnalyzer() {
			@Override
			public AnalyzerDescriptor descriptor() {
				return AnalyzerDescriptor
					.builder("mismatch", "m", "1", AnalysisCategory.SECURITY, CapabilityLevel.BASIC)
					.build();
			}

			@Override
			public Applicability applicability(AnalysisContext context) {
				return Applicability.applicable();
			}

			@Override
			public AnalyzerResult analyze(AnalysisContext context) {
				return AnalyzerResult.empty();
			}
		};

		assertThatThrownBy(() -> registry(List.of(mismatched))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CODE_QUALITY")
			.hasMessageContaining("SECURITY");
	}

	// ---- applicability evaluation --------------------------------------------------------------

	@Test
	void evaluatesEveryAnalyzerInIsolation() {
		StubAnalyzer applicable = code("ok");
		StubAnalyzer notApplicable = code("no").applicability(Applicability.notApplicable());
		StubAnalyzer unavailable = code("missing-tool")
			.applicability(Applicability.unavailable("Semgrep CLI not found"));
		StubAnalyzer throwing = code("boom").applicability(ctx -> {
			throw new IllegalStateException("secret internal detail");
		});
		StubAnalyzer nullResult = code("null").applicability(ctx -> null);
		AnalyzerRegistry registry = registry(List.of(applicable, notApplicable, unavailable, throwing, nullResult));

		Map<String, Applicability> result = registry.evaluate(context());

		assertThat(result.get("ok").kind()).isEqualTo(Applicability.Kind.APPLICABLE);
		assertThat(result.get("no").kind()).isEqualTo(Applicability.Kind.NOT_APPLICABLE);
		assertThat(result.get("missing-tool")).isEqualTo(Applicability.unavailable("Semgrep CLI not found"));
		assertThat(result.get("boom").kind()).isEqualTo(Applicability.Kind.UNAVAILABLE);
		assertThat(result.get("boom").reason()).contains("IllegalStateException")
			.doesNotContain("secret internal detail");
		assertThat(result.get("null").kind()).isEqualTo(Applicability.Kind.UNAVAILABLE);
	}

	@Test
	void disabledAnalyzersAreMarkedDisabledWithoutBeingAsked() {
		StubAnalyzer skipped = code("skipped");
		AnalyzerRegistry registry = new AnalyzerRegistry(List.of(), List.of(skipped), disabled("skipped"));

		Applicability result = registry.evaluate(context()).get("skipped");

		assertThat(result.kind()).isEqualTo(Applicability.Kind.DISABLED);
		assertThat(result.reason()).isEqualTo("disabled by configuration");
		assertThat(skipped.applicabilityCalls()).isZero();
		assertThat(registry.isEnabled("skipped")).isFalse();
	}

	// ---- settings binding ----------------------------------------------------------------------

	@Test
	void settingsBindFromAnalysisAnalyzersProperties() {
		AnalyzerSettings settings = new Binder(new MapConfigurationPropertySource(
				Map.of("analysis.analyzers.semgrep.enabled", "false", "analysis.analyzers.pmd-java.options.max-files",
						"250", "analysis.analyzers.pmd-java.options.ruleset", "custom.xml")))
			.bind("analysis", AnalyzerSettings.class)
			.get();

		assertThat(settings.isEnabled("semgrep")).isFalse();
		assertThat(settings.isEnabled("pmd-java")).isTrue();
		assertThat(settings.isEnabled("anything-unconfigured")).isTrue();
		assertThat(settings.intOption("pmd-java", "max-files", 10)).isEqualTo(250);
		assertThat(settings.option("pmd-java", "ruleset", "default.xml")).isEqualTo("custom.xml");
		assertThat(settings.option("pmd-java", "missing", "fallback")).isEqualTo("fallback");
		assertThat(settings.intOption("unknown", "x", 7)).isEqualTo(7);
	}

	@Test
	void anInvalidNumericOptionFallsBackToTheDefault() {
		AnalyzerSettings settings = new Binder(new MapConfigurationPropertySource(
				Map.of("analysis.analyzers.pmd-java.options.max-files", "many")))
			.bind("analysis", AnalyzerSettings.class)
			.get();

		assertThat(settings.intOption("pmd-java", "max-files", 10)).isEqualTo(10);
	}

}
