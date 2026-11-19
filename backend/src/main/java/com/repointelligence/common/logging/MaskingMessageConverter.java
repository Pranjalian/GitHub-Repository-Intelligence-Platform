package com.repointelligence.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.repointelligence.common.security.SecretRedactor;

/** Logback {@code %maskedMessage}: the formatted log message with secrets redacted. */
public class MaskingMessageConverter extends MessageConverter {

	@Override
	public String convert(ILoggingEvent event) {
		return SecretRedactor.redact(super.convert(event));
	}

}
