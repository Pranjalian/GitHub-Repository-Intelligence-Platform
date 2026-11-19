package com.repointelligence.analysis.capability;

import com.repointelligence.analysis.domain.TechnologyKind;
import com.repointelligence.analysis.registry.AnalyzerRegistry;
import com.repointelligence.analysis.spi.Analyzer;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerSettings;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.DetectedTechnology;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.ExecutionStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds the capability matrix: what the platform can and cannot do for the technologies that were
 * actually detected. Detection is not support, so the matrix states analyzer depth and status
 * separately and says plainly when nothing exists.
 *
 * <ul>
 * <li>Rows exist only for detected technologies: a React repository has no Java rows.
 * <li>A language gets a row for every analyzer that supports it, technology-independent ones
 * included, and an {@code UNSUPPORTED} row for every category no analyzer covers.
 * <li>Frameworks, build tools and package managers get rows only from analyzers that name them (for
 * example Maven for dependencies); they get no "unsupported" noise. Databases, test frameworks and
 * deployment indicators are informational and have no rows.
 * <li>An analyzer that is not applicable to the snapshot has no row; an unavailable or disabled one
 * does, with the reason.
 * <li>With no language detected, technology-independent analyzers are listed under
 * {@value #NO_LANGUAGE} so their coverage is still visible.
 * </ul>
 */
@Component
public class CapabilityMatrixBuilder {

	public static final String NO_LANGUAGE = "Any language";

	private static final Set<TechnologyKind> SUBJECT_KINDS = EnumSet.of(TechnologyKind.LANGUAGE, TechnologyKind.FRAMEWORK,
			TechnologyKind.BUILD_TOOL, TechnologyKind.PACKAGE_MANAGER);

	private static final List<AnalysisCategory> MATRIX_CATEGORIES = List.of(AnalysisCategory.STRUCTURE,
			AnalysisCategory.DEPENDENCIES, AnalysisCategory.CODE_QUALITY, AnalysisCategory.SECURITY,
			AnalysisCategory.TESTS);

	private final AnalyzerRegistry registry;

	private final AnalyzerSettings settings;

	public CapabilityMatrixBuilder(AnalyzerRegistry registry, AnalyzerSettings settings) {
		this.registry = registry;
		this.settings = settings;
	}

	/**
	 * @param technologies what the detectors found
	 * @param applicability the result of {@link AnalyzerRegistry#evaluate} for this snapshot
	 * @return rows with {@code NOT_ANALYZED} for analyzers that will run; fill them in afterwards
	 * with {@link #applyOutcomes}
	 */
	public List<CapabilityRow> build(List<DetectedTechnology> technologies, Map<String, Applicability> applicability) {
		Map<String, DetectedTechnology> subjects = new LinkedHashMap<>();
		for (DetectedTechnology technology : technologies) {
			if (SUBJECT_KINDS.contains(technology.kind())) {
				subjects.putIfAbsent(technology.kind() + ":" + technology.technology().toLowerCase(Locale.ROOT), technology);
			}
		}
		List<CapabilityRow> rows = new ArrayList<>();
		boolean anyLanguage = false;
		for (DetectedTechnology technology : subjects.values()) {
			boolean language = technology.kind() == TechnologyKind.LANGUAGE;
			anyLanguage |= language;
			Set<AnalysisCategory> covered = EnumSet.noneOf(AnalysisCategory.class);
			for (Analyzer analyzer : registry.analyzers()) {
				AnalyzerDescriptor descriptor = analyzer.descriptor();
				boolean applies = descriptor.isTechnologyIndependent() ? language : descriptor.supports(technology);
				if (!applies) {
					continue;
				}
				CapabilityRow row = rowFor(technology.technology(), analyzer, applicability);
				if (row != null) {
					rows.add(row);
					covered.add(descriptor.category());
				}
			}
			if (language) {
				for (AnalysisCategory category : MATRIX_CATEGORIES) {
					if (!covered.contains(category)) {
						rows.add(new CapabilityRow(technology.technology(), category, null, null,
								CapabilityLevel.UNSUPPORTED, ExecutionStatus.NOT_SUPPORTED,
								"no analyzer pack for " + technology.technology()));
					}
				}
			}
		}
		if (!anyLanguage) {
			for (Analyzer analyzer : registry.analyzers()) {
				if (analyzer.descriptor().isTechnologyIndependent()) {
					CapabilityRow row = rowFor(NO_LANGUAGE, analyzer, applicability);
					if (row != null) {
						rows.add(row);
					}
				}
			}
		}
		rows.sort(Comparator.comparing((CapabilityRow row) -> row.technology().toLowerCase(Locale.ROOT))
			.thenComparing(CapabilityRow::category)
			.thenComparing(row -> row.analyzerId() == null ? "\uffff" : row.analyzerId()));
		return rows;
	}

	/** Fills in how analyzers actually ended; only rows still {@code NOT_ANALYZED} with an analyzer change. */
	public List<CapabilityRow> applyOutcomes(List<CapabilityRow> rows, Map<String, ExecutionOutcome> outcomes) {
		List<CapabilityRow> updated = new ArrayList<>(rows.size());
		for (CapabilityRow row : rows) {
			ExecutionOutcome outcome = row.analyzerId() == null ? null : outcomes.get(row.analyzerId());
			if (outcome != null && row.executionStatus() == ExecutionStatus.NOT_ANALYZED) {
				updated.add(row.withExecution(outcome.status(), outcome.reason()));
			}
			else {
				updated.add(row);
			}
		}
		return updated;
	}

	private CapabilityRow rowFor(String technology, Analyzer analyzer, Map<String, Applicability> applicability) {
		AnalyzerDescriptor descriptor = analyzer.descriptor();
		String id = descriptor.id();
		if (!settings.isEnabled(id)) {
			return unavailableRow(technology, descriptor, ExecutionStatus.NOT_CONFIGURED, "disabled by configuration");
		}
		Applicability result = applicability.get(id);
		if (result == null) {
			throw new IllegalStateException("No applicability was evaluated for analyzer " + id);
		}
		return switch (result.kind()) {
			case NOT_APPLICABLE -> null;
			case DISABLED -> unavailableRow(technology, descriptor, ExecutionStatus.NOT_CONFIGURED, "disabled by configuration");
			case UNAVAILABLE -> unavailableRow(technology, descriptor, ExecutionStatus.UNAVAILABLE, result.reason());
			case APPLICABLE -> new CapabilityRow(technology, descriptor.category(), id, descriptor.version(),
					descriptor.capabilityLevel(), ExecutionStatus.NOT_ANALYZED, null);
		};
	}

	private static CapabilityRow unavailableRow(String technology, AnalyzerDescriptor descriptor,
			ExecutionStatus status, String reason) {
		return new CapabilityRow(technology, descriptor.category(), descriptor.id(), descriptor.version(),
				CapabilityLevel.UNAVAILABLE, status, reason);
	}

}
