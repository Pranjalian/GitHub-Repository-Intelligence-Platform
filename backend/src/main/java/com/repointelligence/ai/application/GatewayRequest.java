package com.repointelligence.ai.application;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.prompt.PromptTemplate;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * What a feature asks the gateway for. The feature supplies the template and the candidate evidence in
 * priority order; the gateway scrubs, budgets, assembles and sends it.
 *
 * @param evidence candidate data blocks, most important first; ids are reassigned E1..En in this order
 * @param runId the analysis run whose stored secret findings are withheld (and, without a {@code budget},
 * whose call budget applies)
 * @param hostedPermitted the repository's policy for hosted providers
 * @param budget a call budget other than the per-run insight budget (e.g. Q&A per snapshot); null for the
 * per-run budget
 */
public record GatewayRequest(PromptTemplate template, List<EvidenceBlock> evidence, UUID repositoryId,
		UUID snapshotId, UUID runId, String commitSha, String preferredProvider, boolean hostedPermitted,
		CallBudget budget) {

	public GatewayRequest(PromptTemplate template, List<EvidenceBlock> evidence, UUID repositoryId, UUID snapshotId,
			UUID runId, String commitSha, String preferredProvider, boolean hostedPermitted) {
		this(template, evidence, repositoryId, snapshotId, runId, commitSha, preferredProvider, hostedPermitted, null);
	}

	/**
	 * A provider-call budget of a feature. Usage records of calls under such a budget carry no run id, so they
	 * never count against an analysis run's insight budget.
	 *
	 * @param key identifies the budget (calls in flight are counted per key)
	 * @param limit provider calls allowed (cache hits are free)
	 * @param used provider calls already recorded against it
	 * @param exhausted the reason shown when the limit is reached
	 */
	public record CallBudget(String key, int limit, LongSupplier used, String exhausted) {
	}

}
