package com.repointelligence.analysis.generic;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.support.InMemoryWorkspace;
import org.junit.jupiter.api.Test;

class GenericTestDiscoveryAnalyzerTests {

	private final GenericTestDiscoveryAnalyzer analyzer = new GenericTestDiscoveryAnalyzer();

	@Test
	void countsTestFilesByConventionWithoutFrameworkClaims() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/App.test.tsx", "test('x', () => {})")
			.add("pkg/foo_test.go", "package foo")
			.add("tests/test_api.py", "def test_x(): pass")
			.add("src/App.tsx", "export {}");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.GENERIC);
		assertThat(result.testRecords()).hasSize(3);
		assertThat(result.testRecords()).allSatisfy(t -> {
			assertThat(t.framework()).isNull();
			assertThat(t.testMethodCount()).isNull();
		});
		assertThat(result.coverageRecords()).isEmpty();
	}

	@Test
	void notApplicableWithoutNonJavaTestFiles() {
		assertThat(analyzer.applicability(new InMemoryWorkspace().add("src/main/java/A.java", "class A {}").context()).kind())
			.isEqualTo(Applicability.Kind.NOT_APPLICABLE);
	}

}
