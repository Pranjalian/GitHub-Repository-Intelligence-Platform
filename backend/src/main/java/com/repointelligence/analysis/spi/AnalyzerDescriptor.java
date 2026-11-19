package com.repointelligence.analysis.spi;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What an analyzer (or detector) declares about itself: identity, version, what it supports, how
 * deep it goes, the tools it needs and its known limitations. The report shows all of this, so it
 * must be honest.
 *
 * <p>
 * An analyzer with no supported languages and no supported frameworks is technology-independent
 * (a {@code GENERIC} analyzer such as the file-structure or secret scanner).
 */
public record AnalyzerDescriptor(String id, String name, String version, AnalysisCategory category,
		Set<String> supportedLanguages, Set<String> supportedFrameworks, Set<String> supportedManifests,
		CapabilityLevel capabilityLevel, List<String> requiredTools, String description, List<String> limitations) {

	public AnalyzerDescriptor {
		requireText(id, "id");
		requireText(name, "name");
		requireText(version, "version");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(capabilityLevel, "capabilityLevel");
		supportedLanguages = copy(supportedLanguages);
		supportedFrameworks = copy(supportedFrameworks);
		supportedManifests = copy(supportedManifests);
		requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
		limitations = limitations == null ? List.of() : List.copyOf(limitations);
		description = description == null ? "" : description;
	}

	public static Builder builder(String id, String name, String version, AnalysisCategory category,
			CapabilityLevel level) {
		return new Builder(id, name, version, category, level);
	}

	/** {@code id@version}: the unit of the analyzer-set hash. */
	public String idAtVersion() {
		return id + "@" + version;
	}

	/** True when no language or framework is declared: the analyzer works on any repository. */
	public boolean isTechnologyIndependent() {
		return supportedLanguages.isEmpty() && supportedFrameworks.isEmpty();
	}

	/**
	 * Whether this analyzer declares support for a detected technology. Languages are matched against
	 * {@link #supportedLanguages()}; frameworks, build tools and package managers against
	 * {@link #supportedFrameworks()}. Matching ignores case.
	 */
	public boolean supports(DetectedTechnology technology) {
		String name = technology.technology().toLowerCase(Locale.ROOT);
		Set<String> candidates = technology.kind() == TechnologyKind.LANGUAGE ? supportedLanguages
				: supportedFrameworks;
		return candidates.stream().anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT).equals(name));
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Analyzer descriptor " + field + " must not be blank");
		}
	}

	private static Set<String> copy(Set<String> values) {
		return values == null ? Set.of() : Set.copyOf(values);
	}

	public static final class Builder {

		private final String id;

		private final String name;

		private final String version;

		private final AnalysisCategory category;

		private final CapabilityLevel level;

		private final Set<String> languages = new LinkedHashSet<>();

		private final Set<String> frameworks = new LinkedHashSet<>();

		private final Set<String> manifests = new LinkedHashSet<>();

		private List<String> requiredTools = List.of();

		private String description = "";

		private List<String> limitations = List.of();

		private Builder(String id, String name, String version, AnalysisCategory category, CapabilityLevel level) {
			this.id = id;
			this.name = name;
			this.version = version;
			this.category = category;
			this.level = level;
		}

		public Builder languages(String... values) {
			languages.addAll(Arrays.asList(values));
			return this;
		}

		public Builder frameworks(String... values) {
			frameworks.addAll(Arrays.asList(values));
			return this;
		}

		public Builder manifests(String... values) {
			manifests.addAll(Arrays.asList(values));
			return this;
		}

		public Builder requiredTools(String... values) {
			requiredTools = List.of(values);
			return this;
		}

		public Builder description(String value) {
			description = value;
			return this;
		}

		public Builder limitations(String... values) {
			limitations = List.of(values);
			return this;
		}

		public AnalyzerDescriptor build() {
			return new AnalyzerDescriptor(id, name, version, category, languages, frameworks, manifests, level,
					requiredTools, description, limitations);
		}

	}

}
