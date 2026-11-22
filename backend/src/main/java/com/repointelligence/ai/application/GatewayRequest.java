package com.repointelligence.ai.application;

import com.repointelligence.ai.prompt.EvidenceBlock;
import com.repointelligence.ai.prompt.PromptTemplate;
import java.util.List;
import java.util.UUID;

/**
 * What a feature asks the gateway for. The feature supplies the template and the candidate evidence in
 * priority order; the gateway scrubs, budgets, assembles and sends it.
 *
 * @param evidence candidate data blocks, most important first; ids are reassigned E1..En in this order
 * @param runId the analysis run whose call budget applies
 * @param hostedPermitted the repository's policy for hosted providers
 */
public record GatewayRequest(PromptTemplate template, List<EvidenceBlock> evidence, UUID repositoryId,
		UUID snapshotId, UUID runId, String commitSha, String preferredProvider, boolean hostedPermitted) {
}
