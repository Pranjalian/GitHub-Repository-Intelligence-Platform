package com.repointelligence.repository.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code forceRescan} is optional and defaults to false. It is a {@code Boolean} because Jackson 3
 * rejects a missing primitive record component.
 *
 * <p>
 * No {@code @URL} validation on purpose: it rejects the supported scheme-less form. The URL is
 * validated by {@code GitHubUrlParser}.
 */
public record AnalyzeRepositoryRequest(
		@Schema(description = "A public GitHub repository URL.", example = "https://github.com/spring-projects/spring-petclinic") @NotBlank @Size(max = 500) String url,
		@Schema(description = "Re-analyze even if this commit was already analyzed.", example = "false") Boolean forceRescan) {

	public AnalyzeRepositoryRequest {
		if (forceRescan == null) {
			forceRescan = Boolean.FALSE;
		}
	}

}
