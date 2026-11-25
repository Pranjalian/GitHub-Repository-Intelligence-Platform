package com.repointelligence.common.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repointelligence.support.EmbeddedPostgresConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code auth.enabled=false} (the default) must leave the application exactly as it was before MVP 8: open, no
 * cookies, no CSRF check, no extra headers. Pinned to false explicitly so the check also holds when the whole suite
 * is run with the login on.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@AutoConfigureMockMvc(addFilters = true)
@TestPropertySource(properties = { "auth.enabled=false", "auth.username=", "auth.password-hash=" })
class AuthDisabledTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void sessionEndpointSaysNoLoginIsRequiredAndSetsNoCookie() throws Exception {
		mockMvc.perform(get("/api/v1/auth/session"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authRequired").value(false))
			.andExpect(jsonPath("$.authenticated").value(false))
			.andExpect(jsonPath("$.username").doesNotExist())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void loginIsNotAvailableAndLogoutIsHarmless() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType("application/json")
			.content("{\"username\":\"operator\",\"password\":\"anything\"}")).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
	}

	@Test
	void everyOtherEndpointIsOpenWithoutASessionOrCsrfTokenAndAddsNoSecurityHeaders() throws Exception {
		mockMvc.perform(get("/api/v1/repositories"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", nullValue()))
			.andExpect(header().doesNotExist("Set-Cookie"))
			.andExpect(header().doesNotExist("X-Frame-Options"));
		mockMvc.perform(post("/api/v1/analysis-runs/" + UUID.randomUUID() + "/cancel"))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

}
