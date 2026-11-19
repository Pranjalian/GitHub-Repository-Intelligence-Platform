package com.repointelligence.repository.application;

import com.repointelligence.analysis.application.AnalyzerSetHashProvider;
import com.repointelligence.analysis.domain.AnalysisRequestedEvent;
import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.exception.AnalysisLimitException;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.common.exception.TooManyJobsException;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.common.validation.GitHubRepositoryRef;
import com.repointelligence.common.validation.GitHubUrlParser;
import com.repointelligence.repository.api.AnalyzeRepositoryResponse;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.github.GitHubClient;
import com.repointelligence.repository.infrastructure.github.GitHubRepositoryInfo;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Submission of a repository for analysis. The commit is resolved synchronously, before anything is
 * stored, so an unknown, private or oversized repository fails immediately. GitHub is called outside
 * any database transaction; only the short persistence step runs in one.
 */
@Service
public class RepositorySubmissionService {

	private static final Logger log = LoggerFactory.getLogger(RepositorySubmissionService.class);

	private static final Set<RunStatus> IN_FLIGHT = Set.of(RunStatus.QUEUED, RunStatus.INGESTING, RunStatus.ANALYZING);

	private static final Set<RunStatus> COMPLETED = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_WARNINGS);

	private final GitHubUrlParser urlParser;

	private final GitHubClient github;

	private final SubmissionRateLimiter rateLimiter;

	private final SubmissionProperties submissionProperties;

	private final IngestionProperties ingestionProperties;

	private final AnalyzerSetHashProvider analyzerSetHash;

	private final TrackedRepositoryRepository repositories;

	private final RepositorySnapshotRepository snapshots;

	private final AnalysisRunRepository runs;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transaction;

	public RepositorySubmissionService(GitHubUrlParser urlParser, GitHubClient github,
			SubmissionRateLimiter rateLimiter, SubmissionProperties submissionProperties,
			IngestionProperties ingestionProperties, AnalyzerSetHashProvider analyzerSetHash,
			TrackedRepositoryRepository repositories, RepositorySnapshotRepository snapshots,
			AnalysisRunRepository runs, ApplicationEventPublisher events, PlatformTransactionManager txManager) {
		this.urlParser = urlParser;
		this.github = github;
		this.rateLimiter = rateLimiter;
		this.submissionProperties = submissionProperties;
		this.ingestionProperties = ingestionProperties;
		this.analyzerSetHash = analyzerSetHash;
		this.repositories = repositories;
		this.snapshots = snapshots;
		this.runs = runs;
		this.events = events;
		this.transaction = new TransactionTemplate(txManager);
	}

	public AnalyzeRepositoryResponse submit(String url, boolean forceRescan) {
		rateLimiter.acquireOrThrow();
		GitHubRepositoryRef ref = urlParser.parse(url);

		GitHubRepositoryInfo info = github.getRepository(ref.owner(), ref.name());
		List<String> warnings = checkEligibility(info);
		String branch = ref.requestedBranch() != null ? ref.requestedBranch() : info.defaultBranch();
		if (branch == null || branch.isBlank()) {
			throw new ResourceNotFoundException("Repository has no default branch to analyze.");
		}
		String sha = github.resolveCommitSha(ref.owner(), ref.name(), branch);

		return persistWithRetry(info, sha, branch, forceRescan, warnings);
	}

	/** Public repositories only; archived repositories are analyzed with a warning. */
	private List<String> checkEligibility(GitHubRepositoryInfo info) {
		// The server's own token may be able to see private repositories; they must still be refused.
		if (!"public".equalsIgnoreCase(info.visibility())) {
			throw new ResourceNotFoundException("Repository not found or not public.");
		}
		if (info.disabled()) {
			throw new AnalysisLimitException("Repository is disabled on GitHub and cannot be analyzed.");
		}
		long maxKb = ingestionProperties.maxRepositorySizeMb() * 1024L;
		if (info.sizeKb() > maxKb) {
			long sizeMb = (info.sizeKb() + 1023) / 1024;
			throw new AnalysisLimitException("Repository is about " + sizeMb + " MB, which exceeds the limit of "
					+ ingestionProperties.maxRepositorySizeMb() + " MB.");
		}
		List<String> warnings = new ArrayList<>();
		if (info.archived()) {
			warnings.add("Repository is archived on GitHub; the analysis reflects its final, read-only state.");
		}
		return warnings;
	}

	/** Two concurrent submissions can race on the unique repository/snapshot rows: retry once. */
	private AnalyzeRepositoryResponse persistWithRetry(GitHubRepositoryInfo info, String sha, String branch,
			boolean force, List<String> warnings) {
		try {
			return transaction.execute(status -> persist(info, sha, branch, force, warnings));
		}
		catch (DataIntegrityViolationException ex) {
			log.debug("Concurrent submission detected; retrying once");
			return transaction.execute(status -> persist(info, sha, branch, force, warnings));
		}
	}

	private AnalyzeRepositoryResponse persist(GitHubRepositoryInfo info, String sha, String branch, boolean force,
			List<String> warnings) {
		TrackedRepository repository = upsertRepository(info);
		RepositorySnapshot snapshot = snapshots.findByRepositoryIdAndCommitSha(repository.getId(), sha)
			.orElseGet(() -> createSnapshot(repository, sha, branch));

		// A run that is already queued or running is always returned, even for a forced rescan.
		Optional<AnalysisRun> inFlight = runs.findBySnapshotIdAndStatusIn(snapshot.getId(), IN_FLIGHT)
			.stream()
			.max(Comparator.comparing(AnalysisRun::getRequestedAt));
		if (inFlight.isPresent()) {
			return response(repository, snapshot, inFlight.get(), true, warnings);
		}
		String currentHash = analyzerSetHash.currentHash();
		if (!force) {
			Optional<AnalysisRun> completed = runs.findTopBySnapshotIdAndStatusInOrderByRequestedAtDesc(
					snapshot.getId(), COMPLETED);
			if (completed.isPresent() && Objects.equals(completed.get().getAnalyzerSetHash(), currentHash)) {
				return response(repository, snapshot, completed.get(), true, warnings);
			}
		}

		if (runs.countByStatusIn(IN_FLIGHT) >= submissionProperties.maxPendingJobs()) {
			throw new TooManyJobsException("The analysis queue is full. Try again later.");
		}
		AnalysisRun run = new AnalysisRun(snapshot, RunStatus.QUEUED);
		run.setForceRescan(force);
		run.setAnalyzerSetHash(currentHash);
		runs.saveAndFlush(run);
		// Delivered to listeners after this transaction commits (see AnalysisRequestedEvent).
		events.publishEvent(new AnalysisRequestedEvent(run.getId()));
		return response(repository, snapshot, run, false, warnings);
	}

	private TrackedRepository upsertRepository(GitHubRepositoryInfo info) {
		TrackedRepository repository = repositories
			.findByHostAndOwnerIgnoreCaseAndNameIgnoreCase("github.com", info.owner(), info.name())
			.orElseGet(() -> new TrackedRepository(info.owner(), info.name(),
					"https://github.com/" + info.owner() + "/" + info.name()));
		// Adopt GitHub's canonical casing and current metadata.
		repository.setOwner(info.owner());
		repository.setName(info.name());
		repository.setCanonicalUrl("https://github.com/" + info.owner() + "/" + info.name());
		repository.setDefaultBranch(info.defaultBranch());
		repository.setVisibility(info.visibility());
		repository.setGithubRepoId(info.id());
		return repositories.saveAndFlush(repository);
	}

	private RepositorySnapshot createSnapshot(TrackedRepository repository, String sha, String branch) {
		RepositorySnapshot snapshot = new RepositorySnapshot(repository, sha);
		snapshot.setBranch(branch);
		return snapshots.saveAndFlush(snapshot);
	}

	private static AnalyzeRepositoryResponse response(TrackedRepository repository, RepositorySnapshot snapshot,
			AnalysisRun run, boolean reused, List<String> warnings) {
		return new AnalyzeRepositoryResponse(repository.getId(), snapshot.getId(), snapshot.getCommitSha(),
				snapshot.getBranch(), run.getId(), run.getStatus(), reused, List.copyOf(warnings));
	}

}
