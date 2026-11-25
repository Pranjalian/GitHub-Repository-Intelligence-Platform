package com.repointelligence.report.application;

import com.repointelligence.analysis.domain.AnalysisFinding;
import com.repointelligence.analysis.domain.DependencyRecord;
import com.repointelligence.analysis.domain.Ecosystem;
import com.repointelligence.analysis.domain.VulnerabilityStatus;
import com.repointelligence.common.exception.BadRequestException;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.Severity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Query building for the findings and dependencies endpoints. Every query is pinned to one snapshot
 * and one run, so another snapshot's rows can never leak in; sort keys are a fixed whitelist.
 */
final class ReportSpecifications {

	static final String SORT_SEVERITY = "severity";

	private static final List<String> FINDING_SORTS = List.of(SORT_SEVERITY, "file", "category", "rule");

	private ReportSpecifications() {
	}

	static Specification<AnalysisFinding> findings(UUID snapshotId, UUID runId, AnalysisCategory category,
			Severity severity, String module, String file, String analyzer, String text, String sort) {
		String sortKey = sort == null || sort.isBlank() ? SORT_SEVERITY : sort.trim().toLowerCase();
		if (!FINDING_SORTS.contains(sortKey)) {
			throw new BadRequestException("Unknown sort '" + sort + "'. Allowed: " + String.join(", ", FINDING_SORTS) + ".");
		}
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			where.add(cb.equal(root.get("snapshot").get("id"), snapshotId));
			where.add(cb.equal(root.get("run").get("id"), runId));
			if (category != null) {
				where.add(cb.equal(root.get("category"), category));
			}
			if (severity != null) {
				where.add(cb.equal(root.get("severity"), severity));
			}
			if (module != null && !module.isBlank()) {
				where.add(cb.equal(root.get("modulePath"), module.trim()));
			}
			if (file != null && !file.isBlank()) {
				String path = file.trim();
				where.add(cb.or(cb.equal(root.get("filePath"), path),
						cb.like(root.get("filePath"), escapeLike(path.endsWith("/") ? path : path + "/") + "%", '\\')));
			}
			if (analyzer != null && !analyzer.isBlank()) {
				where.add(cb.equal(root.get("analyzerId"), analyzer.trim()));
			}
			if (text != null && !text.isBlank()) {
				// case-insensitive substring over what the findings list shows: title, rule id and path
				String pattern = "%" + escapeLike(text.trim().toLowerCase()) + "%";
				where.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
						cb.like(cb.lower(root.get("ruleId")), pattern, '\\'),
						cb.like(cb.lower(cb.coalesce(root.<String>get("filePath"), "")), pattern, '\\')));
			}
			// Count queries must not carry an ORDER BY.
			if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
				List<Order> order = new ArrayList<>();
				Expression<Integer> severityRank = cb.<Integer>selectCase()
					.when(cb.equal(root.get("severity"), Severity.CRITICAL), 0)
					.when(cb.equal(root.get("severity"), Severity.HIGH), 1)
					.when(cb.equal(root.get("severity"), Severity.MEDIUM), 2)
					.when(cb.equal(root.get("severity"), Severity.LOW), 3)
					.otherwise(4);
				switch (sortKey) {
					case "file" -> order.add(cb.asc(root.get("filePath")));
					case "category" -> order.add(cb.asc(root.get("category")));
					case "rule" -> order.add(cb.asc(root.get("ruleId")));
					default -> order.add(cb.asc(severityRank));
				}
				if (!SORT_SEVERITY.equals(sortKey)) {
					order.add(cb.asc(severityRank));
				}
				order.add(cb.asc(root.get("filePath")));
				order.add(cb.asc(root.get("startLine")));
				order.add(cb.asc(root.get("id")));
				query.orderBy(order);
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

	static Specification<DependencyRecord> dependencies(UUID snapshotId, UUID runId, Ecosystem ecosystem,
			String module, String scope, Boolean vulnerable) {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			where.add(cb.equal(root.get("snapshot").get("id"), snapshotId));
			where.add(cb.equal(root.get("run").get("id"), runId));
			if (ecosystem != null) {
				where.add(cb.equal(root.get("ecosystem"), ecosystem));
			}
			if (module != null && !module.isBlank()) {
				where.add(cb.equal(root.get("modulePath"), module.trim()));
			}
			if (scope != null && !scope.isBlank()) {
				where.add(cb.equal(root.get("scope"), scope.trim()));
			}
			if (vulnerable != null) {
				Predicate isVulnerable = cb.equal(root.get("vulnerabilityStatus"), VulnerabilityStatus.VULNERABLE);
				where.add(vulnerable ? isVulnerable : cb.not(isVulnerable));
			}
			if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
				query.orderBy(cb.asc(root.get("name")), cb.asc(root.get("manifestPath")), cb.asc(root.get("id")));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
