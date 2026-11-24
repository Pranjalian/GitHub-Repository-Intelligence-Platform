package com.repointelligence.impact.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Findings and coverage facts that feed the risk score; always scoped by snapshot and run. */
@Repository
public class ImpactContextQueries {

	private final NamedParameterJdbcTemplate jdbc;

	public ImpactContextQueries(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** {high or critical, other} security findings located in the given files. */
	public int[] securityFindings(UUID snapshotId, UUID runId, Collection<String> filePaths) {
		if (filePaths.isEmpty()) {
			return new int[] { 0, 0 };
		}
		int[] counts = new int[2];
		List<String> paths = new ArrayList<>(filePaths);
		for (int i = 0; i < paths.size(); i += 5_000) {
			jdbc.query("""
					SELECT severity, count(*) FROM analysis_finding
					WHERE snapshot_id = :snapshot AND run_id = :run AND category = 'SECURITY' AND file_path IN (:paths)
					GROUP BY severity
					""", new MapSqlParameterSource("snapshot", snapshotId).addValue("run", runId)
				.addValue("paths", paths.subList(i, Math.min(paths.size(), i + 5_000))), rs -> {
					String severity = rs.getString(1);
					counts[severity.equals("CRITICAL") || severity.equals("HIGH") ? 0 : 1] += rs.getInt(2);
				});
		}
		return counts;
	}

	/** Line coverage (0..1) of a class from a committed report, or null when no report names it. */
	public Double lineCoverage(UUID snapshotId, UUID runId, String typeFqn) {
		List<double[]> rows = jdbc.query("""
				SELECT COALESCE(line_covered, 0), COALESCE(line_missed, 0) FROM coverage_record
				WHERE snapshot_id = :snapshot AND run_id = :run AND scope = 'CLASS' AND name IN (:names)
				""", new MapSqlParameterSource("snapshot", snapshotId).addValue("run", runId)
			.addValue("names", List.of(typeFqn, typeFqn.replace('.', '/'))), (rs, i) -> new double[] { rs.getInt(1), rs.getInt(2) });
		double covered = rows.stream().mapToDouble(r -> r[0]).sum();
		double total = covered + rows.stream().mapToDouble(r -> r[1]).sum();
		return total == 0 ? null : covered / total;
	}

}
