package com.repointelligence.analysis.spi;

import static com.repointelligence.support.StubAnalyzers.context;
import static com.repointelligence.support.StubAnalyzers.tech;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.AnalysisMethod;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.repository.domain.FileType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpiTests {

	// ---- descriptor ----------------------------------------------------------------------------

	@Test
	void descriptorMatchesLanguagesAndFrameworksIgnoringCase() {
		AnalyzerDescriptor descriptor = AnalyzerDescriptor
			.builder("maven-dependencies", "Maven dependencies", "1.0.0", AnalysisCategory.DEPENDENCIES,
					CapabilityLevel.DEEP)
			.languages("Java")
			.frameworks("Maven", "Spring Boot")
			.manifests("pom.xml")
			.requiredTools("none")
			.description("d")
			.limitations("no transitive dependencies")
			.build();

		assertThat(descriptor.supports(tech("java", TechnologyKind.LANGUAGE))).isTrue();
		assertThat(descriptor.supports(tech("MAVEN", TechnologyKind.BUILD_TOOL))).isTrue();
		assertThat(descriptor.supports(tech("Spring Boot", TechnologyKind.FRAMEWORK))).isTrue();
		assertThat(descriptor.supports(tech("TypeScript", TechnologyKind.LANGUAGE))).isFalse();
		// a language name in the framework slot (and vice versa) does not match
		assertThat(descriptor.supports(tech("Maven", TechnologyKind.LANGUAGE))).isFalse();
		assertThat(descriptor.supports(tech("Java", TechnologyKind.FRAMEWORK))).isFalse();
		assertThat(descriptor.isTechnologyIndependent()).isFalse();
		assertThat(descriptor.idAtVersion()).isEqualTo("maven-dependencies@1.0.0");
		assertThat(descriptor.limitations()).containsExactly("no transitive dependencies");
	}

	@Test
	void descriptorWithoutLanguagesOrFrameworksIsTechnologyIndependent() {
		AnalyzerDescriptor generic = AnalyzerDescriptor
			.builder("generic-structure", "Structure", "1", AnalysisCategory.STRUCTURE, CapabilityLevel.GENERIC)
			.build();

		assertThat(generic.isTechnologyIndependent()).isTrue();
		assertThat(generic.supports(tech("Java", TechnologyKind.LANGUAGE))).isFalse();
		assertThat(generic.description()).isEmpty();
	}

	@Test
	void descriptorRejectsBlankIdentity() {
		assertThatThrownBy(() -> AnalyzerDescriptor
			.builder(" ", "n", "1", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC)
			.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AnalyzerDescriptor
			.builder("id", "n", "", AnalysisCategory.CODE_QUALITY, CapabilityLevel.BASIC)
			.build()).isInstanceOf(IllegalArgumentException.class);
	}

	// ---- results -------------------------------------------------------------------------------

	@Test
	void resultBuilderIsCompletedUntilAWarningIsAdded() {
		AnalyzerResult clean = AnalyzerResult.builder().build();
		AnalyzerResult warned = AnalyzerResult.builder()
			.finding(new FindingResult(AnalysisCategory.CODE_QUALITY, "rule", Severity.LOW, Confidence.DETECTED, "t",
					null, "A.java", null, 1, 2, null, AnalysisMethod.PARSER, null))
			.warning("1 file failed to parse")
			.limitation("no auxclasspath")
			.build();

		assertThat(clean.status()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(AnalyzerResult.empty().findings()).isEmpty();
		assertThat(warned.status()).isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(warned.findings()).hasSize(1);
		assertThat(warned.findings().get(0).evidence()).isEmpty();
		assertThat(warned.warnings()).containsExactly("1 file failed to parse");
		assertThat(warned.limitations()).containsExactly("no auxclasspath");
	}

	@Test
	void anAnalyzerResultCannotClaimAFailureStatus() {
		assertThatThrownBy(
				() -> new AnalyzerResult(ExecutionStatus.FAILED, null, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resultCollectionsAreImmutableCopies() {
		AnalyzerResult result = AnalyzerResult.builder().warning("w").build();

		assertThatThrownBy(() -> result.warnings().add("x")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.findings().add(null)).isInstanceOf(UnsupportedOperationException.class);
	}

	// ---- context -------------------------------------------------------------------------------

	@Test
	void contextHelpersFilterFilesAndFindTechnologies() {
		AnalysisContext base = context();
		List<FileInfo> files = List.of(new FileInfo("A.java", "Java", FileType.SOURCE, 10, "h", false, null),
				new FileInfo("logo.png", null, FileType.OTHER, 10, "h", true, null),
				new FileInfo("big.txt", null, FileType.OTHER, 99999, "h", false, "exceeds max-file-size-kb (4 KB)"));
		AnalysisContext withFiles = new AnalysisContext(null, null, null, "0".repeat(40), base.workspace(), files,
				List.of(), null, null, null);

		AnalysisContext detected = withFiles.withTechnologies(List.of(tech("Java", TechnologyKind.LANGUAGE)));

		assertThat(withFiles.extractedFiles()).extracting(FileInfo::path).containsExactly("A.java");
		assertThat(withFiles.technologies()).isEmpty();
		assertThat(detected.hasTechnology("java")).isTrue();
		assertThat(detected.hasTechnology("Python")).isFalse();
		assertThat(detected.technology("JAVA")).isPresent();
		assertThat(detected.files()).isEqualTo(withFiles.files());
		assertThat(detected.settings()).isNotNull();
		assertThat(detected.cancellation().isCancelled()).isFalse();
		assertThat(detected.remaining()).isPositive();
	}

	@Test
	void cancellationIsObservedByAnalyzersAndOwnedByTheOrchestrator() {
		CancellationSource source = new CancellationSource();
		CancellationToken token = source;

		token.throwIfCancelled();
		source.cancel();

		assertThat(token.isCancelled()).isTrue();
		assertThatThrownBy(token::throwIfCancelled).isInstanceOf(AnalysisCancelledException.class);
		assertThat(CancellationToken.NONE.isCancelled()).isFalse();
	}

	@Test
	void applicabilityFactories() {
		assertThat(Applicability.applicable().isApplicable()).isTrue();
		assertThat(Applicability.notApplicable().isApplicable()).isFalse();
		assertThat(Applicability.unavailable("missing").reason()).isEqualTo("missing");
		assertThat(Applicability.disabled().kind()).isEqualTo(Applicability.Kind.DISABLED);
	}

}
