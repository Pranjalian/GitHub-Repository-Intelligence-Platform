package com.repointelligence.architecture.application;

import com.repointelligence.architecture.domain.ArchitectureLayer;
import com.repointelligence.architecture.domain.EntityRelationshipType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Produces Mermaid source for the architecture report: a layer diagram, a module diagram, the
 * API → controller → service → repository → entity flow and an entity-relationship diagram. Inputs
 * are repository-derived and untrusted, so all text goes through {@link MermaidWriter}. Each diagram is
 * capped (the most connected nodes first) and says so in a note when it is truncated.
 */
@Service
public class ArchitectureDiagramService {

	static final int MAX_LAYER_NODES = 40;

	static final int MAX_FLOW_ENDPOINTS = 15;

	static final int MAX_FLOW_NODES = 45;

	static final int MAX_ENTITIES = 30;

	static final int MAX_MODULES = 40;

	private static final List<ArchitectureLayer> LAYER_ORDER = List.of(ArchitectureLayer.CONTROLLER,
			ArchitectureLayer.SERVICE, ArchitectureLayer.REPOSITORY, ArchitectureLayer.ENTITY, ArchitectureLayer.DTO,
			ArchitectureLayer.CONFIGURATION, ArchitectureLayer.INFRASTRUCTURE);

	/** A type with its layer (null when unassigned). */
	public record TypeNode(UUID id, String fqn, String name, ArchitectureLayer layer) {
	}

	/** A resolved type-to-type relationship, e.g. injection. */
	public record Link(UUID from, UUID to) {
	}

	public record EndpointNode(String httpMethod, String route, UUID controller) {
	}

	public record EntityLink(UUID from, UUID to, EntityRelationshipType type, String field) {
	}

	public record ModuleLink(String from, String to) {
	}

	public record Input(List<TypeNode> types, List<Link> injections, List<EndpointNode> endpoints,
			List<EntityLink> entityLinks, List<ModuleLink> moduleLinks) {
	}

	/** Mermaid sources; a diagram is null when there is nothing to draw. */
	public record Diagrams(String layers, String modules, String apiFlow, String entities, List<String> notes) {
	}

	public Diagrams generate(Input input) {
		List<String> notes = new ArrayList<>();
		Map<UUID, TypeNode> types = new LinkedHashMap<>();
		input.types().forEach(t -> types.put(t.id(), t));
		return new Diagrams(layers(input, types, notes), modules(input, notes), apiFlow(input, types, notes),
				entities(input, types, notes), notes);
	}

	// ── layers ───────────────────────────────────────────────────────────────────────────────────

	private String layers(Input input, Map<UUID, TypeNode> types, List<String> notes) {
		List<TypeNode> layered = types.values().stream().filter(t -> t.layer() != null).toList();
		if (layered.isEmpty()) {
			return null;
		}
		Map<UUID, Integer> degree = degree(input);
		List<TypeNode> shown = layered.stream()
			.sorted(Comparator.comparingInt((TypeNode t) -> -degree.getOrDefault(t.id(), 0)).thenComparing(TypeNode::fqn))
			.limit(MAX_LAYER_NODES)
			.toList();
		if (shown.size() < layered.size()) {
			notes.add("Layer diagram: " + shown.size() + " of " + layered.size()
					+ " classes are shown (the most connected first).");
		}
		Set<UUID> visible = new HashSet<>();
		shown.forEach(t -> visible.add(t.id()));
		MermaidWriter mermaid = new MermaidWriter("flowchart TB", "n");
		int group = 0;
		for (ArchitectureLayer layer : LAYER_ORDER) {
			List<TypeNode> members = shown.stream().filter(t -> t.layer() == layer).sorted(Comparator.comparing(TypeNode::name)).toList();
			if (members.isEmpty()) {
				continue;
			}
			mermaid.line("  subgraph L" + (++group) + "[\"" + MermaidWriter.label(title(layer)) + "\"]");
			members.forEach(t -> mermaid.node(t.id().toString(), t.name(), "    "));
			mermaid.line("  end");
		}
		Set<String> drawn = new HashSet<>();
		for (Link link : input.injections()) {
			if (visible.contains(link.from()) && visible.contains(link.to()) && !link.from().equals(link.to())
					&& drawn.add(link.from() + ">" + link.to())) {
				mermaid.edge(mermaid.id(link.from().toString()), mermaid.id(link.to().toString()), null);
			}
		}
		for (EntityLink link : input.entityLinks()) {
			if (link.type() == EntityRelationshipType.REPOSITORY_FOR && link.to() != null && visible.contains(link.from())
					&& visible.contains(link.to()) && drawn.add(link.from() + ">" + link.to())) {
				mermaid.edge(mermaid.id(link.from().toString()), mermaid.id(link.to().toString()), "manages");
			}
		}
		return mermaid.toString();
	}

	private static Map<UUID, Integer> degree(Input input) {
		Map<UUID, Integer> degree = new HashMap<>();
		for (Link link : input.injections()) {
			degree.merge(link.from(), 1, Integer::sum);
			degree.merge(link.to(), 1, Integer::sum);
		}
		for (EntityLink link : input.entityLinks()) {
			degree.merge(link.from(), 1, Integer::sum);
			if (link.to() != null) {
				degree.merge(link.to(), 1, Integer::sum);
			}
		}
		for (EndpointNode endpoint : input.endpoints()) {
			degree.merge(endpoint.controller(), 1, Integer::sum);
		}
		return degree;
	}

	private static String title(ArchitectureLayer layer) {
		String name = layer.name().toLowerCase(Locale.ROOT);
		return layer == ArchitectureLayer.DTO ? "DTO" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	// ── modules ──────────────────────────────────────────────────────────────────────────────────

	private String modules(Input input, List<String> notes) {
		if (input.moduleLinks().isEmpty()) {
			return null;
		}
		Set<String> modules = new LinkedHashSet<>();
		input.moduleLinks().forEach(l -> {
			modules.add(l.from());
			modules.add(l.to());
		});
		List<String> shown = modules.stream().sorted().limit(MAX_MODULES).toList();
		if (shown.size() < modules.size()) {
			notes.add("Module diagram: " + shown.size() + " of " + modules.size() + " modules are shown.");
		}
		MermaidWriter mermaid = new MermaidWriter("flowchart LR", "m");
		shown.forEach(m -> mermaid.node(m, m, "  "));
		for (ModuleLink link : input.moduleLinks()) {
			if (mermaid.has(link.from()) && mermaid.has(link.to())) {
				mermaid.edge(mermaid.id(link.from()), mermaid.id(link.to()), null);
			}
		}
		return mermaid.toString();
	}

	// ── API flow ─────────────────────────────────────────────────────────────────────────────────

	private String apiFlow(Input input, Map<UUID, TypeNode> types, List<String> notes) {
		if (input.endpoints().isEmpty()) {
			return null;
		}
		List<EndpointNode> endpoints = input.endpoints()
			.stream()
			.sorted(Comparator.comparing(EndpointNode::route).thenComparing(EndpointNode::httpMethod))
			.toList();
		List<EndpointNode> shown = endpoints.stream().limit(MAX_FLOW_ENDPOINTS).toList();
		if (shown.size() < endpoints.size()) {
			notes.add("API flow: " + shown.size() + " of " + endpoints.size() + " endpoints are shown.");
		}
		Map<UUID, List<UUID>> injects = new HashMap<>();
		input.injections().forEach(l -> injects.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to()));
		Map<UUID, List<UUID>> manages = new HashMap<>();
		input.entityLinks()
			.stream()
			.filter(l -> l.type() == EntityRelationshipType.REPOSITORY_FOR && l.to() != null)
			.forEach(l -> manages.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to()));

		MermaidWriter mermaid = new MermaidWriter("flowchart LR", "f");
		Set<String> drawn = new HashSet<>();
		int[] nodes = { 0 };
		boolean truncated = false;
		for (EndpointNode endpoint : shown) {
			TypeNode controller = types.get(endpoint.controller());
			if (controller == null) {
				continue;
			}
			String endpointId = mermaid.node("endpoint:" + endpoint.httpMethod() + " " + endpoint.route(),
					endpoint.httpMethod() + " " + endpoint.route(), "  ");
			nodes[0]++;
			String controllerId = flowNode(mermaid, controller, nodes);
			link(mermaid, drawn, endpointId, controllerId);
			for (UUID serviceId : injects.getOrDefault(controller.id(), List.of())) {
				TypeNode service = types.get(serviceId);
				if (service == null || service.layer() != ArchitectureLayer.SERVICE) {
					continue;
				}
				if (nodes[0] >= MAX_FLOW_NODES && !mermaid.has(service.id().toString())) {
					truncated = true;
					continue;
				}
				link(mermaid, drawn, controllerId, flowNode(mermaid, service, nodes));
				for (UUID repositoryId : injects.getOrDefault(service.id(), List.of())) {
					TypeNode repository = types.get(repositoryId);
					if (repository == null || repository.layer() != ArchitectureLayer.REPOSITORY) {
						continue;
					}
					if (nodes[0] >= MAX_FLOW_NODES && !mermaid.has(repository.id().toString())) {
						truncated = true;
						continue;
					}
					String repositoryNode = flowNode(mermaid, repository, nodes);
					link(mermaid, drawn, mermaid.id(service.id().toString()), repositoryNode);
					for (UUID entityId : manages.getOrDefault(repository.id(), List.of())) {
						TypeNode entity = types.get(entityId);
						if (entity != null && (nodes[0] < MAX_FLOW_NODES || mermaid.has(entity.id().toString()))) {
							link(mermaid, drawn, repositoryNode, flowNode(mermaid, entity, nodes));
						}
					}
				}
			}
		}
		if (truncated) {
			notes.add("API flow: capped at " + MAX_FLOW_NODES + " nodes.");
		}
		return mermaid.toString();
	}

	private static String flowNode(MermaidWriter mermaid, TypeNode type, int[] nodes) {
		if (!mermaid.has(type.id().toString())) {
			nodes[0]++;
		}
		return mermaid.node(type.id().toString(), type.name(), "  ");
	}

	private static void link(MermaidWriter mermaid, Set<String> drawn, String from, String to) {
		if (drawn.add(from + ">" + to)) {
			mermaid.edge(from, to, null);
		}
	}

	// ── entities ─────────────────────────────────────────────────────────────────────────────────

	private String entities(Input input, Map<UUID, TypeNode> types, List<String> notes) {
		List<TypeNode> entities = types.values()
			.stream()
			.filter(t -> t.layer() == ArchitectureLayer.ENTITY)
			.sorted(Comparator.comparing(TypeNode::name))
			.toList();
		if (entities.isEmpty()) {
			return null;
		}
		List<TypeNode> shown = entities.stream().limit(MAX_ENTITIES).toList();
		if (shown.size() < entities.size()) {
			notes.add("Entity diagram: " + shown.size() + " of " + entities.size() + " entities are shown.");
		}
		Map<UUID, String> names = new LinkedHashMap<>();
		Set<String> used = new HashSet<>();
		for (TypeNode entity : shown) {
			String name = MermaidWriter.identifier(entity.name(), "Entity");
			String unique = name;
			for (int n = 2; !used.add(unique); n++) {
				unique = name + "_" + n;
			}
			names.put(entity.id(), unique);
		}
		StringBuilder out = new StringBuilder("erDiagram\n");
		names.values().forEach(name -> out.append("  ").append(name).append(" {\n  }\n"));
		for (EntityLink link : input.entityLinks()) {
			if (link.type() == EntityRelationshipType.REPOSITORY_FOR || link.to() == null || !names.containsKey(link.from())
					|| !names.containsKey(link.to())) {
				continue;
			}
			String cardinality = switch (link.type()) {
				case ONE_TO_MANY -> "||--o{";
				case MANY_TO_ONE -> "}o--||";
				case ONE_TO_ONE -> "||--||";
				case MANY_TO_MANY -> "}o--o{";
				case REPOSITORY_FOR -> throw new IllegalStateException();
			};
			out.append("  ")
				.append(names.get(link.from()))
				.append(' ')
				.append(cardinality)
				.append(' ')
				.append(names.get(link.to()))
				.append(" : \"")
				.append(MermaidWriter.label(link.field() == null ? "" : link.field()))
				.append("\"\n");
		}
		return out.toString();
	}

}
