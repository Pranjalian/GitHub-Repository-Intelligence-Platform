package com.repointelligence.repository.infrastructure.github;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the GitHub client. The outbound allowlist ({@code allowedHosts}) covers the API
 * host and every redirect target; nothing outside it is ever contacted.
 */
@Validated
@ConfigurationProperties("github")
public record GitHubProperties(
		@NotBlank @DefaultValue("https://api.github.com") String apiBaseUrl,
		/** Optional. Raises the unauthenticated limit (60/h) to 5,000/h. Never logged. */
		@DefaultValue("") String token,
		@NotBlank @DefaultValue("2022-11-28") String apiVersion,
		@NotNull @DefaultValue("PT5S") Duration connectTimeout,
		@NotNull @DefaultValue("PT30S") Duration readTimeout,
		@NotEmpty @DefaultValue({ "api.github.com", "codeload.github.com" }) List<String> allowedHosts,
		/** Redirect targets must use https. Disable only for tests against a local stub. */
		@DefaultValue("true") boolean requireHttps,
		@Min(0) @Max(10) @DefaultValue("3") int maxRedirects,
		@Valid @DefaultValue Retry retry) {

	public record Retry(
			@Min(1) @Max(10) @DefaultValue("3") int maxAttempts,
			@NotNull @DefaultValue("PT0.5S") Duration initialBackoff,
			@NotNull @DefaultValue("PT5S") Duration maxBackoff) {
	}

	public boolean hasToken() {
		return token != null && !token.isBlank();
	}

	/** Never includes the token. */
	@Override
	public String toString() {
		return "GitHubProperties[apiBaseUrl=" + apiBaseUrl + ", token=" + (hasToken() ? "***" : "<none>")
				+ ", allowedHosts=" + allowedHosts + "]";
	}

}
