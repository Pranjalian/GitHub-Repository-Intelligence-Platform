package com.repointelligence;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repointelligence.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocumentationAndHealthTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthIsPublicAndUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			// details are hidden outside the dev profile
			.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void onlyHealthAndInfoAreExposed() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
	}

	@Test
	void openApiDocumentDescribesTheApplication() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
			.andExpect(jsonPath("$.info.title").value("GitHub Repository Intelligence Platform"))
			.andExpect(jsonPath("$.info.version").value("0.0.1"))
			.andExpect(jsonPath("$.info.description").isNotEmpty())
			// only the endpoints the UI needs, no placeholders for future phases
			.andExpect(jsonPath("$.paths", aMapWithSize(11)))
			.andExpect(jsonPath("$.paths['/api/v1/repositories/analyze']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}/snapshots']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/analysis-runs/{runId}']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/analysis-runs/{runId}/cancel']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}/reports/{snapshotId}']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/findings']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/dependencies']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/tests']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/coverage']").exists());
	}

	@Test
	void swaggerUiLoads() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
		mockMvc.perform(get("/swagger-ui/index.html"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Swagger UI")));
	}

}
