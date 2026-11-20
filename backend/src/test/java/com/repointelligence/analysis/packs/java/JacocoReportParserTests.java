package com.repointelligence.analysis.packs.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.CoverageScope;
import com.repointelligence.analysis.spi.CoverageResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JacocoReportParserTests {

	static final String REPORT = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
			<report name="demo">
			  <package name="com/example">
			    <class name="com/example/Foo" sourcefilename="Foo.java">
			      <counter type="LINE" missed="4" covered="21"/>
			      <counter type="BRANCH" missed="6" covered="10"/>
			    </class>
			    <class name="com/example/NoBranches">
			      <counter type="LINE" missed="1" covered="0"/>
			    </class>
			    <counter type="LINE" missed="5" covered="21"/>
			    <counter type="BRANCH" missed="6" covered="10"/>
			  </package>
			  <counter type="LINE" missed="5" covered="21"/>
			  <counter type="BRANCH" missed="6" covered="10"/>
			</report>
			""";

	private static List<CoverageResult> parse(String xml) throws Exception {
		return JacocoReportParser.parse("target/site/jacoco/jacoco.xml", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void parsesBundlePackageAndClassCounters() throws Exception {
		List<CoverageResult> records = parse(REPORT);

		assertThat(records).extracting(CoverageResult::scope).containsExactly(CoverageScope.BUNDLE, CoverageScope.PACKAGE,
				CoverageScope.CLASS, CoverageScope.CLASS);
		CoverageResult foo = records.get(2);
		assertThat(foo.name()).isEqualTo("com/example/Foo");
		assertThat(foo.lineCovered()).isEqualTo(21);
		assertThat(foo.lineMissed()).isEqualTo(4);
		assertThat(foo.branchCovered()).isEqualTo(10);
		assertThat(foo.branchMissed()).isEqualTo(6);
		assertThat(foo.reportPath()).isEqualTo("target/site/jacoco/jacoco.xml");
	}

	@Test
	void anAbsentCounterIsNullNotZero() throws Exception {
		CoverageResult noBranches = parse(REPORT).get(3);

		assertThat(noBranches.lineCovered()).isEqualTo(0);
		assertThat(noBranches.branchCovered()).isNull();
		assertThat(noBranches.branchMissed()).isNull();
	}

	@Test
	void externalEntitiesAndDtdsAreNeverResolved(@TempDir Path dir) throws Exception {
		Path secret = dir.resolve("secret.txt");
		Files.writeString(secret, "TOP-SECRET-CONTENT");
		String xxe = "<?xml version=\"1.0\"?>\n<!DOCTYPE report [\n  <!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">\n"
				+ "  <!ENTITY remote SYSTEM \"http://127.0.0.1:9/never\">\n]>\n"
				+ "<report name=\"&xxe;&remote;\"><counter type=\"LINE\" missed=\"1\" covered=\"2\"/></report>";

		String everything;
		try {
			everything = parse(xxe).toString();
		}
		catch (Exception ex) {
			everything = String.valueOf(ex.getMessage());
		}

		assertThat(everything).doesNotContain("TOP-SECRET-CONTENT");
	}

	@Test
	void anExternalDtdReferenceIsNotFetched() throws Exception {
		String withDtd = "<?xml version=\"1.0\"?><!DOCTYPE report SYSTEM \"http://127.0.0.1:9/evil.dtd\"><report name=\"x\"/>";

		assertThat(parse(withDtd)).hasSize(1);
	}

}
