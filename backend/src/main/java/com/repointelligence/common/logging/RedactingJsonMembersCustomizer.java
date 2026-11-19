package com.repointelligence.common.logging;

import com.repointelligence.common.security.SecretRedactor;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Redacts secrets from every string value in structured (JSON) log output. Enabled in the prod profile. */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

	@Override
	public void customize(JsonWriter.Members<Object> members) {
		members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, SecretRedactor::redact));
	}

}
