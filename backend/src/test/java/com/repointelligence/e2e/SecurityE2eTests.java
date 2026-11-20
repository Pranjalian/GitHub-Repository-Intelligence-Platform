package com.repointelligence.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.repointelligence.support.E2eTestBase;
import com.repointelligence.support.SecurityFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The security-smells fixture: secrets are found everywhere and never leak into the database, API or logs. */
@ExtendWith(OutputCaptureExtension.class)
class SecurityE2eTests extends E2eTestBase {

	@Test
	void secretsAreFoundWithLocationAndNeverStoredLoggedOrReturned(CapturedOutput output) throws Exception {
		Analysis analysis = analyze("acme", "security-smells", "6".repeat(40), SecurityFixture.tarball().build());

		assertThat(analysis.status()).isIn("COMPLETED", "COMPLETED_WITH_WARNINGS");
		String findings = findings(analysis, "&category=SECURITY");
		List<String> rules = JsonPath.read(findings, "$.items[*].ruleId");
		assertThat(rules).contains("aws-access-key-id", "github-token", "slack-token", "stripe-api-key", "jwt",
				"private-key", "generic-quoted-credential", "config-file-credential");
		assertThat((List<String>) JsonPath.read(findings, "$.items[?(@.ruleId=='github-token')].filePath"))
			.containsExactly("src/main/java/demo/Config.java");
		assertThat((List<Integer>) JsonPath.read(findings, "$.items[?(@.ruleId=='github-token')].startLine")).containsExactly(5);
		assertThat((List<String>) JsonPath.read(findings, "$.items[?(@.ruleId=='github-token')].sourceUrl"))
			.allMatch(url -> url.contains("/blob/" + "6".repeat(40) + "/src/main/java/demo/Config.java#L5"));
		assertThat((List<String>) JsonPath.read(findings, "$.items[*].fingerprint")).isNotEmpty();
		// placeholders in the fixture are not flagged
		assertThat((List<String>) JsonPath.read(findings, "$.items[?(@.filePath=='src/main/resources/application.yml')].startLine"
				.replace("startLine", "ruleId"))).containsExactly("config-file-credential");

		String stored = jdbc.queryForObject("SELECT coalesce(string_agg(title || ' ' || coalesce(description,'') || ' '"
				+ " || coalesce(evidence::text,'') || ' ' || coalesce(suggested_next_step,'') || ' ' || rule_id, ' '), '')"
				+ " FROM analysis_finding", String.class);
		String everythingReturned = findings + report(analysis) + dependencies(analysis, "")
				+ getJson("/api/v1/analysis-runs/" + analysis.runId());
		for (String secret : SecurityFixture.ALL_SECRETS) {
			assertThat(stored).as("database must not contain " + secret.substring(0, 4) + "...").doesNotContain(secret);
			assertThat(everythingReturned).as("API must not contain " + secret.substring(0, 4) + "...").doesNotContain(secret);
			assertThat(output.getAll()).as("logs must not contain " + secret.substring(0, 4) + "...").doesNotContain(secret);
		}
	}

	@Test
	void semgrepIsUnavailableOrRunsAndTheSecretScannerAlwaysRuns() throws Exception {
		Analysis analysis = analyze("acme", "security-smells", "7".repeat(40), SecurityFixture.tarball().build());

		String report = report(analysis);

		assertThat((List<String>) JsonPath.read(report, "$.sections[4].analyzers[*].id"))
			.contains("generic-secret-scan-analyzer", "semgrep-security-analyzer");
		List<String> semgrep = JsonPath.read(report, "$.sections[?(@.category=='SECURITY')].analyzers[?(@.id=='semgrep-security-analyzer')].status");
		assertThat(semgrep).hasSize(1);
		assertThat(semgrep.get(0)).isIn("UNAVAILABLE", "COMPLETED", "COMPLETED_WITH_WARNINGS");
		assertThat((List<String>) JsonPath.read(report, "$.sections[?(@.category=='SECURITY')].analyzers[?(@.id=='generic-secret-scan-analyzer')].status"))
			.containsExactly("COMPLETED");
		if (semgrep.get(0).equals("UNAVAILABLE")) {
			assertThat((List<String>) JsonPath.read(report, "$.limitations[?(@.analyzerId=='semgrep-security-analyzer')].reason"))
				.anyMatch(reason -> reason.contains("Semgrep CLI not found"));
		}
	}

}
