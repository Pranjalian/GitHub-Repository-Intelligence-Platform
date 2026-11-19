package com.repointelligence.analysis.spi;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-analyzer switches and options from {@code analysis.analyzers.<id>.*}. An analyzer is enabled
 * unless configured otherwise.
 */
@Validated
@ConfigurationProperties("analysis")
public record AnalyzerSettings(@NotNull @DefaultValue Map<String, AnalyzerConfig> analyzers) {

	public record AnalyzerConfig(@DefaultValue("true") boolean enabled, @NotNull @DefaultValue Map<String, String> options) {
	}

	public static AnalyzerSettings defaults() {
		return new AnalyzerSettings(Map.of());
	}

	public boolean isEnabled(String analyzerId) {
		AnalyzerConfig config = analyzers.get(analyzerId);
		return config == null || config.enabled();
	}

	public String option(String analyzerId, String key, String defaultValue) {
		AnalyzerConfig config = analyzers.get(analyzerId);
		if (config == null) {
			return defaultValue;
		}
		return config.options().getOrDefault(key, defaultValue);
	}

	public int intOption(String analyzerId, String key, int defaultValue) {
		String value = option(analyzerId, key, null);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException ex) {
			return defaultValue;
		}
	}

}
