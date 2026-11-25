package com.repointelligence.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets the whole existing MockMvc suite run with the login switched on (MVP 8, Step 8.3):
 * {@code ./mvnw verify -Dspring.profiles.active=authon}. Every MockMvc request then carries a logged-in test
 * user and a valid CSRF token, so each test exercises its endpoint behind the real filter chain exactly as a
 * signed-in operator would. Inactive (and absent from the context) when {@code auth.enabled} is not true, so the
 * default run is untouched. Tests that exercise the login itself use a real server instead
 * ({@link AuthTestClient}) and are not affected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
class AuthEnabledMockMvcSupport {

	@Bean
	MockMvcBuilderCustomizer signedInOperatorForEveryRequest() {
		return builder -> builder.defaultRequest(get("/").with(user("operator").roles("OPERATOR")).with(csrf()));
	}

}
