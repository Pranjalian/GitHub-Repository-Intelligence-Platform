package com.repointelligence.analysis.spi;

import com.repointelligence.common.model.Confidence;
import java.util.List;
import java.util.UUID;

/**
 * What an {@link ImpactAnalyzer} found: the affected components with how each was reached, endpoints,
 * related entities and repositories, modules and tests, and the limitations that apply to this particular
 * result. Plain data; the core adds the risk score, links and the optional AI summary.
 */
public record ImpactResult(UUID targetId, String targetKind, String targetLayer, List<AffectedComponent> components,
		List<EndpointImpact> endpoints, List<RelatedData> relatedData, List<ModuleImpact> modules, List<TestImpact> tests,
		List<String> limitations, boolean truncated, int totalDependents, int maxDepthReached) {

	public ImpactResult {
		components = List.copyOf(components);
		endpoints = List.copyOf(endpoints);
		relatedData = List.copyOf(relatedData);
		modules = List.copyOf(modules);
		tests = List.copyOf(tests);
		limitations = List.copyOf(limitations);
	}

	/**
	 * One affected method or type.
	 *
	 * @param kind METHOD or TYPE
	 * @param viaId the component (or the target) it depends on: components form a tree rooted at the target
	 * @param relationships how it depends on {@code viaId}: CALLS, INJECTS, EXTENDS, IMPLEMENTS, FIELD_TYPE, ...
	 * @param confidence the weakest link of the path from the target
	 * @param reason a short statement of why it is listed
	 */
	public record AffectedComponent(UUID id, String kind, String name, String qualifiedName, String layer, int depth,
			UUID viaId, List<String> relationships, Confidence confidence, String reason, String filePath, Integer line,
			Integer endLine, String modulePath) {

		public AffectedComponent {
			relationships = relationships == null ? List.of() : List.copyOf(relationships);
		}

	}

	public record EndpointImpact(String httpMethod, String route, boolean routeResolved, UUID controllerId,
			UUID handlerId, String controller, String handler, String filePath, Integer line, Confidence confidence,
			UUID viaId) {
	}

	/** An entity or repository connected to an affected (or the target) component. */
	public record RelatedData(String kind, UUID id, String qualifiedName, String relationship, String viaName,
			Confidence confidence, String filePath, Integer line) {
	}

	/**
	 * @param reason CONTAINS_AFFECTED (a module with affected symbols) or DEPENDS_ON_TARGET_MODULE
	 */
	public record ModuleImpact(String modulePath, String reason, int depth, int affectedSymbols,
			List<String> evidencePaths, Confidence confidence) {

		public ModuleImpact {
			evidencePaths = evidencePaths == null ? List.of() : List.copyOf(evidencePaths);
		}

	}

	/**
	 * A test that may need to run or change.
	 *
	 * @param relation DIRECT_CALL, TYPE_REFERENCE, NAMING_CONVENTION or AFFECTED_COMPONENT
	 * @param methods test methods that contain the reference
	 * @param via the production component the test refers to, when it is not the target itself
	 */
	public record TestImpact(String testSymbol, String filePath, List<String> methods, String relation,
			Confidence confidence, Integer line, String via) {

		public TestImpact {
			methods = methods == null ? List.of() : List.copyOf(methods);
		}

	}

}
