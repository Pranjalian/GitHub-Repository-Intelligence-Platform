package com.repointelligence.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

class RedactingJsonMembersCustomizerTests {

	private static final String TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";

	@Test
	void redactsStringValuesInStructuredOutput() {
		JsonWriter<Object> writer = JsonWriter.<Object>of(members -> {
			members.add("message", value -> ((Map<?, ?>) value).get("message"));
			members.add("count", value -> ((Map<?, ?>) value).get("count"));
			new RedactingJsonMembersCustomizer().customize(members);
		});

		String json = writer.writeToString(Map.of("message", "failed with token " + TOKEN + " password=hunter2", "count", 3));

		assertThat(json).doesNotContain(TOKEN).doesNotContain("hunter2");
		assertThat(json).contains("***REDACTED***").contains("\"count\":3");
	}

}
