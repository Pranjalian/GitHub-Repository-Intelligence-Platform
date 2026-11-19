package com.repointelligence.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repointelligence.common.logging.RequestCorrelationFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTests {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(new RequestCorrelationFilter())
			.setValidator(validator)
			.build();
	}

	@Test
	void notFoundMapsTo404() throws Exception {
		mockMvc.perform(get("/t/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.detail").value("repository not found or not public"))
			.andExpect(jsonPath("$.instance").value("/t/not-found"))
			.andExpect(jsonPath("$.timestamp").exists())
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void invalidRepositoryUrlMapsTo400() throws Exception {
		mockMvc.perform(get("/t/invalid-url")).andExpect(status().isBadRequest());
	}

	@Test
	void analysisLimitMapsTo422() throws Exception {
		mockMvc.perform(get("/t/limit")).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void tooManyJobsMapsTo429() throws Exception {
		mockMvc.perform(get("/t/too-many")).andExpect(status().isTooManyRequests());
	}

	@Test
	void upstreamUnavailableMapsTo502AndCanBe503() throws Exception {
		mockMvc.perform(get("/t/upstream")).andExpect(status().isBadGateway());
		mockMvc.perform(get("/t/upstream-503")).andExpect(status().isServiceUnavailable());
	}

	@Test
	void upstreamRateLimitMapsTo429WithRetryAfter() throws Exception {
		mockMvc.perform(get("/t/rate-limit"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.retryAfterSeconds").isNumber())
			.andExpect(jsonPath("$.resetAt").exists());
	}

	@Test
	void upstreamRateLimitWithUnknownResetOmitsRetryAfter() throws Exception {
		mockMvc.perform(get("/t/rate-limit-unknown"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.resetAt").doesNotExist());
	}

	@Test
	void unexpectedExceptionMapsTo500WithoutMessageOrStackTrace() throws Exception {
		mockMvc.perform(get("/t/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.requestId").exists())
			.andExpect(content().string(not(containsString("jdbc:postgresql://internal-host"))))
			.andExpect(content().string(not(containsString("RuntimeException"))))
			.andExpect(content().string(not(containsString("stackTrace"))))
			.andExpect(content().string(not(containsString("at com.repointelligence"))));
	}

	@Test
	void requestIdInBodyMatchesResponseHeader() throws Exception {
		String requestId = mockMvc.perform(get("/t/boom").header("X-Request-Id", "req-1234-abcd"))
			.andReturn()
			.getResponse()
			.getHeader("X-Request-Id");
		mockMvc.perform(get("/t/boom").header("X-Request-Id", "req-1234-abcd"))
			.andExpect(jsonPath("$.requestId").value(requestId));
	}

	@Test
	void validationFailureMapsTo400WithFieldDetails() throws Exception {
		mockMvc.perform(post("/t/validate").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.details[0]").value(containsString("url")));
	}

	@Test
	void malformedJsonMapsTo400() throws Exception {
		mockMvc.perform(post("/t/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void unknownRouteMapsTo404() throws Exception {
		mockMvc.perform(get("/t/does-not-exist")).andExpect(status().isNotFound());
	}

	@RestController
	static class ThrowingController {

		record Body(@NotBlank String url) { }

		@GetMapping("/t/not-found")
		void notFound() {
			throw new ResourceNotFoundException("repository not found or not public");
		}

		@GetMapping("/t/invalid-url")
		void invalidUrl() {
			throw new InvalidRepositoryUrlException("bad url");
		}

		@GetMapping("/t/limit")
		void limit() {
			throw new AnalysisLimitException("repository exceeds max-repository-size-mb");
		}

		@GetMapping("/t/too-many")
		void tooMany() {
			throw new TooManyJobsException("job queue is full");
		}

		@GetMapping("/t/upstream")
		void upstream() {
			throw new UpstreamUnavailableException("GitHub unavailable");
		}

		@GetMapping("/t/upstream-503")
		void upstream503() {
			throw new UpstreamUnavailableException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub unavailable");
		}

		@GetMapping("/t/rate-limit")
		void rateLimit() {
			throw new UpstreamRateLimitException("GitHub rate limit reached", Instant.now().plusSeconds(120));
		}

		@GetMapping("/t/rate-limit-unknown")
		void rateLimitUnknown() {
			throw new UpstreamRateLimitException("GitHub rate limit reached", null);
		}

		@GetMapping("/t/boom")
		void boom() {
			throw new RuntimeException("connection to jdbc:postgresql://internal-host:5432/db failed");
		}

		@PostMapping("/t/validate")
		void validate(@Valid @RequestBody Body body) {
		}

	}

}
