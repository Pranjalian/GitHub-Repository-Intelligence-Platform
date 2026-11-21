package com.repointelligence.architecture.api;

import com.repointelligence.analysis.domain.Evidence;
import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.EdgeResolution;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import com.repointelligence.architecture.domain.LayerMethod;
import com.repointelligence.architecture.domain.PatternType;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The architecture of one snapshot (commit). Everything comes from the latest completed run unless a
 * run is named. Raw facts (symbols, endpoints, entities, injection) and inferred statements (layers,
 * chains, cycles, patterns) each carry evidence and a confidence; {@code limitations} says what was
 * not or could not be analysed. When no architecture analyzer applies, {@code supported} is false and
 * the lists are empty.
 */
@Schema(description = "Architecture of one snapshot: layers, endpoints, entities, dependency injection, cycles, "
		+ "pattern candidates and Mermaid diagrams, with confidence, evidence and limitations.")
public record ArchitectureReportDTO(UUID repositoryId, UUID snapshotId, UUID runId, String commitSha,
		@Schema(description = "False when no architecture analyzer exists for the detected technologies.") boolean supported,
		@Schema(description = "Aggregated status of the architecture analyzers; NOT_SUPPORTED when none ran.") ExecutionStatus status,
		List<AnalyzerDTO> analyzers,
		@Schema(description = "Detected languages without architecture support, e.g. 'Python: no analyzer pack for Python'.") List<String> unsupported,
		@Schema(description = "Counts of symbols, edges by resolution, endpoints and entities.") Map<String, Long> summary,
		List<LayerDTO> layers, List<EndpointDTO> endpoints, List<EntityDTO> entities,
		@Schema(description = "Injection relationships between types; UNRESOLVED ones point outside the snapshot.") List<InjectionDTO> injections,
		@Schema(description = "Controller → service → repository → entity chains built from resolved injection edges.") List<List<ChainStepDTO>> diChains,
		List<ModuleDependencyDTO> moduleDependencies, List<CycleDTO> cycles, List<PatternDTO> patterns,
		DiagramsDTO diagrams, List<String> limitations) {

	public record AnalyzerDTO(String id, String version, ExecutionStatus status, CapabilityLevel capabilityLevel) {
	}

	public record LayerDTO(ArchitectureLayer layer,
			@Schema(description = "All classes in the layer; `classes` lists at most 500.") int total,
			List<LayerClassDTO> classes) {
	}

	public record LayerClassDTO(String name, String fullyQualifiedName, String filePath, Integer line,
			Confidence confidence, LayerMethod method, List<Evidence> evidence, String sourceUrl) {
	}

	public record EndpointDTO(String httpMethod, String route,
			@Schema(description = "False when part of the path is not a compile-time constant; it is shown in braces.") boolean routeResolved,
			String controller, String handler, String requestType, String responseType, String filePath, Integer line,
			String sourceUrl) {
	}

	public record EntityDTO(String name, String fullyQualifiedName, String table, String filePath, Integer line,
			String sourceUrl, List<EntityRelationDTO> relationships, List<String> repositories) {
	}

	public record EntityRelationDTO(String field, EntityRelationshipType type, String target, boolean targetResolved,
			Map<String, String> attributes) {
	}

	public record InjectionDTO(String from, String to, EdgeResolution resolution, Confidence confidence, String via,
			String member, String filePath, Integer line, String sourceUrl) {
	}

	public record ChainStepDTO(String name, String fullyQualifiedName, ArchitectureLayer layer) {
	}

	public record ModuleDependencyDTO(String source, String target, List<Evidence> evidence) {
	}

	public record CycleDTO(String rule, Severity severity, Confidence confidence, String title, String description,
			List<String> members, List<Map<String, Object>> edges, String filePath, Integer line, String sourceUrl) {
	}

	public record PatternDTO(PatternType pattern, Confidence confidence, String summary, List<String> symbols,
			Map<String, Object> evidence) {
	}

	@Schema(description = "Mermaid sources (render with securityLevel 'strict'); null when there is nothing to draw.")
	public record DiagramsDTO(String layers, String modules, String apiFlow, String entities, List<String> notes) {
	}

}
