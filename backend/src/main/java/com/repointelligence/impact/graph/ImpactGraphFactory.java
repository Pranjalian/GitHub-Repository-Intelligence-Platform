package com.repointelligence.impact.graph;

import com.repointelligence.analysis.spi.ImpactGraph;
import com.repointelligence.impact.ImpactProperties;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Opens the dependency graph of one run of one snapshot. */
@Component
public class ImpactGraphFactory {

	private final NamedParameterJdbcTemplate jdbc;

	private final ImpactProperties properties;

	public ImpactGraphFactory(NamedParameterJdbcTemplate jdbc, ImpactProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	public ImpactGraph open(UUID snapshotId, UUID runId) {
		return new SnapshotImpactGraph(jdbc, snapshotId, runId, properties.maxGraphRows());
	}

}
