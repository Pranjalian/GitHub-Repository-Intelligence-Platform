package com.repointelligence.analysis.generic;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.FindingResult;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.support.InMemoryWorkspace;
import com.repointelligence.support.SecurityFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenericSecretScanAnalyzerTests {

	private final GenericSecretScanAnalyzer analyzer = new GenericSecretScanAnalyzer();

	private static List<String> rules(List<FindingResult> findings) {
		return findings.stream().map(FindingResult::ruleId).toList();
	}

	@Test
	void runsForEveryRepositoryAtGenericLevel() {
		assertThat(analyzer.descriptor().capabilityLevel()).isEqualTo(CapabilityLevel.GENERIC);
		assertThat(analyzer.descriptor().isTechnologyIndependent()).isTrue();
		assertThat(analyzer.applicability(new InMemoryWorkspace().context())).isEqualTo(Applicability.applicable());
	}

	@Test
	void detectsEachSecretTypeInJavaYamlEnvAndJavaScript() {
		InMemoryWorkspace ws = new InMemoryWorkspace()
			.add("src/Config.java", "String aws = \"" + SecurityFixture.AWS_KEY + "\";\nString gh = \"" + SecurityFixture.GITHUB_TOKEN
					+ "\";\nString password = \"" + SecurityFixture.JAVA_PASSWORD + "\";\n")
			.add("config/application.yml", "db:\n  password: " + SecurityFixture.YAML_PASSWORD + "\n")
			.add(".env", "API_TOKEN=" + SecurityFixture.ENV_TOKEN + "\nSLACK=" + SecurityFixture.SLACK_TOKEN + "\n")
			.add("web/app.js", "const apiKey = \"" + SecurityFixture.JS_API_KEY + "\";\nconst s = \"" + SecurityFixture.STRIPE_KEY
					+ "\";\nconst j = \"" + SecurityFixture.JWT + "\";\n")
			.add("keys/key.txt", SecurityFixture.PRIVATE_KEY_HEADER + "\nbody\n");

		AnalyzerResult result = analyzer.analyze(ws.context());

		assertThat(rules(result.findings())).contains("aws-access-key-id", "github-token", "generic-quoted-credential",
				"config-file-credential", "slack-token", "stripe-api-key", "jwt", "private-key");
		assertThat(result.findings()).filteredOn(f -> f.ruleId().equals("config-file-credential"))
			.extracting(FindingResult::filePath)
			.contains("config/application.yml", ".env");
		assertThat(result.configSummary()).containsKey("filesScanned");
	}

	@Test
	void findingsCarryFileLineAndConfidence() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("src/A.java", "class A {\n  String t = \"" + SecurityFixture.GITHUB_TOKEN + "\";\n}\n");

		FindingResult finding = analyzer.analyze(ws.context()).findings().get(0);

		assertThat(finding.filePath()).isEqualTo("src/A.java");
		assertThat(finding.startLine()).isEqualTo(2);
		assertThat(finding.confidence()).isEqualTo(Confidence.LIKELY);
		assertThat(finding.title()).startsWith("Likely hard-coded secret");
	}

	@Test
	void placeholdersAreNotFlagged() {
		InMemoryWorkspace ws = new InMemoryWorkspace()
			.add("a.yml", "password: changeme\ntoken: ${TOKEN}\nsecret: your-secret-here\napi_key: xxxxxxxxxxxx\n")
			.add("B.java", "String password = \"changeme\";\nString token = \"${TOKEN}\";\nString apiKey = \"xxxxxxxxxxxx\";\n"
					+ "String secret = \"<your-secret>\";\nString pwd = \"password\";\n")
			.add(".env", "DB_PASSWORD=\nAPI_KEY=example\nTOKEN={{ token }}\n")
			.add("doc.txt", "aws key example: AK" + "IAIOSFODNN7EXAMPLE\n");

		assertThat(analyzer.analyze(ws.context()).findings()).isEmpty();
	}

	@Test
	void lowEntropyValuesAreNotFlagged() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("A.java", "String password = \"aaaaaaaaaaaa\";\n");

		assertThat(analyzer.analyze(ws.context()).findings()).isEmpty();
	}

	@Test
	void theRawSecretNeverAppearsInAnyFindingField() {
		InMemoryWorkspace ws = new InMemoryWorkspace()
			.add("src/Config.java", "String aws = \"" + SecurityFixture.AWS_KEY + "\";\nString password = \"" + SecurityFixture.JAVA_PASSWORD + "\";\n")
			.add("application.yml", "password: " + SecurityFixture.YAML_PASSWORD + "\n")
			.add(".env", "API_TOKEN=" + SecurityFixture.ENV_TOKEN + "\n")
			.add("app.js", "const apiKey = \"" + SecurityFixture.JS_API_KEY + "\"; // " + SecurityFixture.STRIPE_KEY + "\n");

		List<FindingResult> findings = analyzer.analyze(ws.context()).findings();

		assertThat(findings).isNotEmpty();
		for (FindingResult finding : findings) {
			String everything = finding + " " + finding.evidence() + " " + finding.title() + " " + finding.description();
			for (String secret : SecurityFixture.ALL_SECRETS) {
				assertThat(everything).doesNotContain(secret);
			}
			assertThat(finding.evidence().get("excerpt").toString()).contains(GenericSecretScanAnalyzer.REDACTED);
		}
	}

	@Test
	void lockFilesAndBinariesAreSkipped() {
		InMemoryWorkspace ws = new InMemoryWorkspace().add("pnpm-lock.yaml", "token: " + SecurityFixture.ENV_TOKEN + "\n")
			.add("dist/app.min.js", "var k=\"" + SecurityFixture.GITHUB_TOKEN + "\"");

		assertThat(analyzer.analyze(ws.context()).findings()).isEmpty();
	}

	@Test
	void shannonEntropy() {
		assertThat(GenericSecretScanAnalyzer.entropy("aaaa")).isZero();
		assertThat(GenericSecretScanAnalyzer.entropy("abcd")).isEqualTo(2.0);
	}

	@Test
	void rulesAreDataDrivenAndALoadedRuleFileNeedsNoCodeChange() {
		GenericSecretScanAnalyzer custom = new GenericSecretScanAnalyzer("/test-secret-rules.yml");

		List<FindingResult> findings = custom.analyze(new InMemoryWorkspace().add("x.txt", "company key: ACME-1234567890abcdef\n").context())
			.findings();

		assertThat(rules(findings)).containsExactly("acme-key");
	}

}
