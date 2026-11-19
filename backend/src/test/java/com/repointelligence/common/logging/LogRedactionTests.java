package com.repointelligence.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.repointelligence.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionTests {

	private static final String TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";

	@Test
	void converterRedactsFormattedMessage() {
		Logger logger = (Logger) LoggerFactory.getLogger("test");
		LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, "calling with {} and password=hunter2",
				null, new Object[] { TOKEN });

		String converted = new MaskingMessageConverter().convert(event);

		assertThat(converted).doesNotContain(TOKEN).doesNotContain("hunter2");
		assertThat(converted).contains("password=***REDACTED***");
	}

	@Test
	void realLoggingConfigurationRedactsConsoleOutput(CapturedOutput output) {
		org.slf4j.Logger log = LoggerFactory.getLogger(LogRedactionTests.class);

		log.info("GitHub call failed, Authorization: Bearer {} token {}", "abc123def456ghi789", TOKEN);

		assertThat(output.getOut()).contains("Authorization: Bearer ***REDACTED***");
		assertThat(output.getOut()).doesNotContain(TOKEN).doesNotContain("abc123def456ghi789");
	}

}
