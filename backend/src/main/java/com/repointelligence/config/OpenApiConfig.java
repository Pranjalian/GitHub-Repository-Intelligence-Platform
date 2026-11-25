package com.repointelligence.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

	@Bean
	OpenAPI repositoryIntelligenceOpenApi(@Value("${app.api.title}") String title,
			@Value("${app.api.version}") String version, @Value("${app.api.description}") String description) {
		return new OpenAPI().info(new Info().title(title).version(version).description(description))
			.components(new Components().addSecuritySchemes("sessionCookie",
					new SecurityScheme().type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.COOKIE)
						.name("REPOINTEL_SESSION")
						.description("Session cookie from POST /api/v1/auth/login. Only required when the server runs "
								+ "with AUTH_ENABLED=true; state-changing calls also need the X-XSRF-TOKEN header.")));
	}

	/** Every operation outside {@code /api/v1/auth/} can answer 401 when the server requires a login. */
	@Bean
	OpenApiCustomizer unauthorizedResponseCustomizer() {
		return openApi -> openApi.getPaths().forEach((path, item) -> {
			if (path.startsWith(AUTH_PATH_PREFIX)) {
				return;
			}
			item.readOperations()
				.forEach(operation -> operation.getResponses()
					.addApiResponse("401", new ApiResponse()
						.description("No valid session (only when the server requires a login, AUTH_ENABLED=true)")));
		});
	}

}
