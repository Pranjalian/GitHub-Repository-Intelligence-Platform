package com.repointelligence.support;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.analysis.spi.TechnologyDetector;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.repository.infrastructure.storage.WorkspaceView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.mockito.Mockito;

/** Test doubles for the analyzer SPI. */
public final class StubAnalyzers {

	private StubAnalyzers() {
	}

	public static StubAnalyzer analyzer(String id, AnalysisCategory category, CapabilityLevel level) {
		return new StubAnalyzer(id, "1.0.0", category, level);
	}

	public static DetectedTechnology tech(String name, TechnologyKind kind) {
		return new DetectedTechnology(name, kind, null, Confidence.DETECTED, List.of(), "test-detector", null);
	}

	public static AnalysisContext context() {
		return new AnalysisContext(null, null, null, "0".repeat(40), Mockito.mock(WorkspaceView.class), List.of(),
				List.of(), null, null, null);
	}

	public static class StubAnalyzer implements Analyzer {

		private final String id;

		private final String version;

		private final AnalysisCategory category;

		private final CapabilityLevel level;

		private String[] languages = {};

		private String[] frameworks = {};

		private Function<AnalysisContext, Applicability> applicability = ctx -> Applicability.applicable();

		private final AtomicInteger applicabilityCalls = new AtomicInteger();

		private Behaviour behaviour = ctx -> AnalyzerResult.empty();

		StubAnalyzer(String id, String version, AnalysisCategory category, CapabilityLevel level) {
			this.id = id;
			this.version = version;
			this.category = category;
			this.level = level;
		}

		public StubAnalyzer languages(String... values) {
			this.languages = values;
			return this;
		}

		public StubAnalyzer frameworks(String... values) {
			this.frameworks = values;
			return this;
		}

		public StubAnalyzer applicability(Applicability value) {
			this.applicability = ctx -> value;
			return this;
		}

		public StubAnalyzer applicability(Function<AnalysisContext, Applicability> value) {
			this.applicability = value;
			return this;
		}

		/** What {@code analyze} does: return a result, throw, block until cancelled, ... */
		public StubAnalyzer behaviour(Behaviour value) {
			this.behaviour = value;
			return this;
		}

		/** A copy of this analyzer with another version (to test that the analyzer-set hash changes). */
		public StubAnalyzer version(String newVersion) {
			StubAnalyzer copy = new StubAnalyzer(id, newVersion, category, level);
			copy.languages = languages;
			copy.frameworks = frameworks;
			copy.applicability = applicability;
			copy.behaviour = behaviour;
			return copy;
		}

		public int applicabilityCalls() {
			return applicabilityCalls.get();
		}

		@Override
		public AnalyzerDescriptor descriptor() {
			return AnalyzerDescriptor.builder(id, id, version, category, level)
				.languages(languages)
				.frameworks(frameworks)
				.build();
		}

		@Override
		public Applicability applicability(AnalysisContext context) {
			applicabilityCalls.incrementAndGet();
			return applicability.apply(context);
		}

		@Override
		public AnalyzerResult analyze(AnalysisContext context) throws Exception {
			return behaviour.run(context);
		}

	}

	@FunctionalInterface
	public interface Behaviour {

		AnalyzerResult run(AnalysisContext context) throws Exception;

	}

	@FunctionalInterface
	public interface DetectBehaviour {

		List<DetectedTechnology> run(AnalysisContext context) throws Exception;

	}

	public static class StubDetector implements TechnologyDetector {

		private final String id;

		private final String version;

		private DetectBehaviour behaviour = ctx -> List.of();

		public StubDetector(String id, String version) {
			this.id = id;
			this.version = version;
		}

		public StubDetector behaviour(DetectBehaviour value) {
			this.behaviour = value;
			return this;
		}

		@Override
		public AnalyzerDescriptor descriptor() {
			return AnalyzerDescriptor.builder(id, id, version, AnalysisCategory.TECHNOLOGY, CapabilityLevel.GENERIC)
				.build();
		}

		@Override
		public List<DetectedTechnology> detect(AnalysisContext context) throws Exception {
			return behaviour.run(context);
		}

	}

}
