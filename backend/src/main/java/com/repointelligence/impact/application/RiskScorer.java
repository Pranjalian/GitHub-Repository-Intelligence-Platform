package com.repointelligence.impact.application;

import com.repointelligence.analysis.spi.ImpactResult;
import com.repointelligence.impact.api.ImpactAnalysisDTO.Risk;
import com.repointelligence.impact.api.ImpactAnalysisDTO.RiskFactor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The heuristic risk score of a change: a sum of visible factor points, each with its value and a plain
 * explanation, out of 100. It ranks changes by how much surface they touch and how little is known to cover
 * it; it is not a probability of failure and the UI never calls it one.
 *
 * <p>
 * Factors and their maximum points: dependency depth (15), number of dependents (25), public API exposure
 * (20), security relevance (20), test presence (15), measured coverage (5).
 */
@Component
public class RiskScorer {

	public static final String LABEL = "Heuristic risk score (0-100). It adds up the factors below; it is not a probability.";

	/**
	 * What the score needs besides the graph result.
	 *
	 * @param highSecurityFindings critical or high security findings in the files of the target and affected components
	 * @param otherSecurityFindings the remaining security findings there
	 * @param lineCoverage line coverage of the target's class from a committed report, or null when there is none
	 * @param testsKnown whether tests were analysed with enough detail to say that none was found
	 */
	public record Inputs(int highSecurityFindings, int otherSecurityFindings, Double lineCoverage, boolean testsKnown) {
	}

	public Risk score(ImpactResult result, Inputs inputs) {
		List<RiskFactor> factors = new ArrayList<>();

		int depth = result.maxDepthReached();
		int depthPoints = Math.min(15, depth * 5);
		factors.add(new RiskFactor("DEPENDENCY_DEPTH", "Dependency depth", depth + (depth == 1 ? " hop" : " hops"), depthPoints, 15,
				depth == 0 ? "Nothing depends on the target within the analysed depth."
						: "The change can travel " + depth + (depth == 1 ? " step" : " steps") + " through callers (5 points per hop, at most 15)."));

		int dependents = result.totalDependents();
		int dependentPoints = dependents == 0 ? 0 : dependents <= 3 ? 5 : dependents <= 10 ? 12 : dependents <= 30 ? 19 : 25;
		factors.add(new RiskFactor("DEPENDENT_COUNT", "Number of dependents", String.valueOf(dependents), dependentPoints, 25,
				"Affected methods or types found: 1-3 score 5, 4-10 score 12, 11-30 score 19, more score 25."
						+ (result.truncated() ? " The list was capped, so the real number is higher." : "")));

		int endpoints = result.endpoints().size();
		int endpointPoints = endpoints == 0 ? 0 : endpoints == 1 ? 10 : endpoints <= 4 ? 15 : 20;
		factors.add(new RiskFactor("PUBLIC_API_EXPOSURE", "Public API exposure", endpoints + (endpoints == 1 ? " endpoint" : " endpoints"),
				endpointPoints, 20, "REST endpoints whose handler reaches the target: 1 scores 10, 2-4 score 15, more score 20."));

		int securityPoints = Math.min(20, inputs.highSecurityFindings() * 8 + inputs.otherSecurityFindings() * 3);
		factors.add(new RiskFactor("SECURITY_RELEVANCE", "Security findings on the path",
				inputs.highSecurityFindings() + " high or critical, " + inputs.otherSecurityFindings() + " other", securityPoints, 20,
				"Security findings stored for the files of the target and the affected components (8 points for each "
						+ "high or critical, 3 for each other). Only analyzers that ran are counted."));

		boolean direct = result.tests().stream().anyMatch(t -> t.relation().equals("DIRECT_CALL") || t.relation().equals("NAMING_CONVENTION"));
		boolean indirect = !result.tests().isEmpty();
		int testPoints;
		String testValue;
		String testExplanation;
		if (direct) {
			testPoints = 0;
			testValue = "tests found that call or are named for the target";
			testExplanation = "At least one test calls the target or follows its naming convention.";
		}
		else if (indirect) {
			testPoints = 8;
			testValue = "only tests of related code";
			testExplanation = "No test names the target; tests exist only for the type or for affected components (8 points).";
		}
		else if (!inputs.testsKnown()) {
			testPoints = 8;
			testValue = "unknown";
			testExplanation = "Test links could not be read for this run, so the absence of tests is not established (8 points).";
		}
		else {
			testPoints = 15;
			testValue = "no test found";
			testExplanation = "No test calls the target, is named for it, or covers an affected component (15 points).";
		}
		factors.add(new RiskFactor("TEST_PRESENCE", "Test presence", testValue, testPoints, 15, testExplanation));

		int coveragePoints;
		String coverageValue;
		String coverageExplanation;
		if (inputs.lineCoverage() == null) {
			coveragePoints = 2;
			coverageValue = "not available";
			coverageExplanation = "No committed coverage report mentions the target's class; coverage is never estimated (2 points).";
		}
		else {
			coveragePoints = (int) Math.round((1 - inputs.lineCoverage()) * 5);
			coverageValue = Math.round(inputs.lineCoverage() * 100) + "% of lines";
			coverageExplanation = "Line coverage of the target's class from a committed report, which may be stale (up to 5 points for none).";
		}
		factors.add(new RiskFactor("COVERAGE", "Measured coverage", coverageValue, coveragePoints, 5, coverageExplanation));

		int score = factors.stream().mapToInt(RiskFactor::points).sum();
		String level = score < 30 ? "LOW" : score < 60 ? "MEDIUM" : "HIGH";
		return new Risk(LABEL, score, 100, level, factors,
				"Based on static analysis only. Reflection, runtime wiring and callers outside this repository are not counted.");
	}

}
