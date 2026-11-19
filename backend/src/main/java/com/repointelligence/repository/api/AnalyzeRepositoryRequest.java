package com.repointelligence.repository.api;

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
public record AnalyzeRepositoryRequest(@NotBlank @Size(max = 500) String url, Boolean forceRescan) {

	public AnalyzeRepositoryRequest {
		if (forceRescan == null) {
			forceRescan = Boolean.FALSE;
		}
	}

}
