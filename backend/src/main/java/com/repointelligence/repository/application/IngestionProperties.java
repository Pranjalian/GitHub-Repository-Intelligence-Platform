package com.repointelligence.repository.application;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Repository ingestion limits. Step 1.5 adds the remaining limits to this block. */
@Validated
@ConfigurationProperties("repository.ingestion")
public record IngestionProperties(@Min(1) @DefaultValue("200") int maxRepositorySizeMb) {
}
