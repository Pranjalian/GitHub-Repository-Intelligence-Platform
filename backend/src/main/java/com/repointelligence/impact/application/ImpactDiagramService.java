package com.repointelligence.impact.application;

import com.repointelligence.analysis.spi.ImpactResult;
import com.repointelligence.analysis.spi.ImpactResult.AffectedComponent;
import com.repointelligence.architecture.application.MermaidWriter;
import com.repointelligence.impact.ImpactProperties;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Diagram;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The dependency chain as a Mermaid flowchart: each affected component points at the component it depends
 * on, down to the target. Names are repository text, so everything goes through {@link MermaidWriter} (synthetic
 * node ids, quoted and escaped labels); the number of drawn nodes is capped and the cap is stated.
 */
@Component
public class ImpactDiagramService {

	private final ImpactProperties properties;

	public ImpactDiagramService(ImpactProperties properties) {
		this.properties = properties;
	}

	public Diagram chain(UUID targetId, String targetName, ImpactResult result) {
		if (result.components().isEmpty()) {
			return new Diagram(null, "Nothing depends on the target within the analysed depth, so there is no chain to draw.");
		}
		List<AffectedComponent> drawn = result.components()
			.stream()
			.sorted(Comparator.comparingInt(AffectedComponent::depth).thenComparing(AffectedComponent::qualifiedName))
			.limit(properties.diagramNodes())
			.toList();
		Set<UUID> drawnIds = drawn.stream().map(AffectedComponent::id).collect(Collectors.toSet());
		MermaidWriter mermaid = new MermaidWriter("flowchart LR", "n");
		String target = mermaid.node(targetId.toString(), targetName, "  ");
		mermaid.line("  style " + target + " stroke-width:3px");
		for (AffectedComponent component : drawn) {
			mermaid.node(component.id().toString(), component.name(), "  ");
		}
		for (AffectedComponent component : drawn) {
			UUID via = component.viaId();
			if (via != null && (via.equals(targetId) || drawnIds.contains(via))) {
				String label = component.confidence().name().equals("DETECTED") ? first(component.relationships())
						: component.confidence().name().toLowerCase(java.util.Locale.ROOT) + " " + first(component.relationships());
				mermaid.edge(mermaid.id(component.id().toString()), mermaid.id(via.toString()), label.strip());
			}
		}
		int omitted = result.components().size() - drawn.size();
		String note = omitted > 0 ? omitted + " more affected component(s) are not drawn (the chain shows the first "
				+ properties.diagramNodes() + " by depth)." : null;
		return new Diagram(mermaid.toString(), note);
	}

	private static String first(List<String> relationships) {
		return relationships.isEmpty() ? "" : relationships.get(0);
	}

}
