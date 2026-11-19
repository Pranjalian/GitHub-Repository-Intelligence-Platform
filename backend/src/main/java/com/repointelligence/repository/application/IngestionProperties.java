package com.repointelligence.repository.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Repository ingestion limits. Nothing here is hard-coded in business logic. */
@Validated
@ConfigurationProperties("repository.ingestion")
public record IngestionProperties(
		/** Pre-check against the size GitHub reports at submission time. */
		@Min(1) @DefaultValue("200") int maxRepositorySizeMb,
		/** Compressed archive size; the download aborts as soon as it is exceeded. */
		@Min(1) @DefaultValue("200") int maxTarballSizeMb,
		/** Total decompressed size of all archive entries (decompression-bomb guard). */
		@Min(1) @DefaultValue("500") int maxExtractedSizeMb,
		/** Larger individual files are catalogued but not extracted. */
		@Min(1) @DefaultValue("1024") int maxFileSizeKb,
		/** Maximum number of catalogued files; the archive entry count is capped at ten times this. */
		@Min(1) @DefaultValue("20000") int maxFileCount,
		@NotEmpty @DefaultValue({ ".git", "node_modules", "target", "build", "dist", ".gradle", "vendor", "__pycache__",
				".idea", ".vscode", ".venv" }) List<String> excludedDirectories,
		/** Ant-style globs that stay extracted even inside an excluded directory (analyzer inputs). */
		@DefaultValue({ "**/jacoco*.xml", "**/jacoco/*.xml" }) List<String> excludedExceptions) {
}
