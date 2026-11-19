package com.repointelligence.repository.application;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Where repository snapshots are extracted, and how long they are kept. */
@Validated
@ConfigurationProperties("repository.workspace")
public record WorkspaceProperties(
		/** Root directory; one subdirectory per snapshot. */
		@NotNull @DefaultValue("./.workspace") Path root,
		/** Keep a workspace this long after its run ends. Zero deletes it immediately. */
		@NotNull @DefaultValue("PT0S") Duration retainAfterAnalysis,
		/** How often orphaned workspaces are swept. */
		@NotNull @DefaultValue("PT1H") Duration sweepInterval) {

	public WorkspaceProperties {
		root = root.toAbsolutePath().normalize();
	}

}
