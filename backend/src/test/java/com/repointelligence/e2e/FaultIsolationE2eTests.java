package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.support.E2eTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** A failing analyzer must not erase the results of the others, and must be listed in the limitations. */
@Import(FaultIsolationE2eTests.FailingAnalyzerConfig.class)
class FaultIsolationE2eTests extends E2eTestBase {

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingAnalyzerConfig {

		@Bean
		Analyzer exploding() {
			return new com.repointelligence.analysis.spi.CodeAnalyzer() {
				@Override
				public AnalyzerDescriptor descriptor() {
					return AnalyzerDescriptor.builder("exploding-code-analyzer", "Exploding", "1.0.0", AnalysisCategory.CODE_QUALITY,
							CapabilityLevel.BASIC).languages("Java").build();
				}

				@Override
				public Applicability applicability(AnalysisContext context) {
					return Applicability.applicable();
				}

				@Override
				public AnalyzerResult analyze(AnalysisContext context) {
					throw new IllegalStateException("boom with password=hunter2hunter2");
				}
			};
		}

	}

	@Test
	void theReportStillHasEverythingElseAndListsTheFailure() throws Exception {
		Analysis analysis = analyze("acme", "spring-boot-simple", "d".repeat(40), fixture("spring-boot-simple").build());

		assertThat(analysis.status()).isEqualTo("COMPLETED_WITH_WARNINGS");
		String report = report(analysis);
		assertThat((List<String>) JsonPath.read(report, "$.limitations[?(@.analyzerId=='exploding-code-analyzer')].status"))
			.containsExactly("FAILED");
		assertThat(JsonPath.read(report, "$.limitations[?(@.analyzerId=='exploding-code-analyzer')].reason").toString())
			.doesNotContain("hunter2hunter2");
		// the other analyzers' results survive
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='DEPENDENCIES')].status")).containsExactly("COMPLETED");
		assertThat((List<String>) JsonPath.read(findings(analysis, "&category=CODE_QUALITY"), "$.items[*].ruleId"))
			.contains("EmptyCatchBlock");
		assertThat((List<String>) JsonPath.read(report, "$.technologies[*].name")).contains("Spring Boot");
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='CODE_QUALITY')].analyzers[*].id"))
			.contains("exploding-code-analyzer");
	}

}
