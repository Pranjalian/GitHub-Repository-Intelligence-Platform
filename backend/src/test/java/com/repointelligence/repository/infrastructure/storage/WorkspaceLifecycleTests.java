package com.repointelligence.repository.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.analysis.infrastructure.persistence.AnalysisRunRepository;
import com.repointelligence.common.model.RunStatus;
import com.repointelligence.repository.application.WorkspaceProperties;
import com.repointelligence.repository.domain.RepositorySnapshot;
import com.repointelligence.repository.domain.TrackedRepository;
import com.repointelligence.repository.infrastructure.persistence.RepositorySnapshotRepository;
import com.repointelligence.repository.infrastructure.persistence.TrackedRepositoryRepository;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Workspace retention and orphan sweeping, including the in-flight protection backed by PostgreSQL. */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class WorkspaceLifecycleTests {

	private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

	@TempDir
	Path root;

	@Autowired
	private TrackedRepositoryRepository repositories;

	@Autowired
	private RepositorySnapshotRepository snapshots;

	@Autowired
	private AnalysisRunRepository runs;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	private WorkspaceProperties properties(Duration retention) {
		return new WorkspaceProperties(root, retention, Duration.ofHours(1));
	}

	private WorkspaceSweeper sweeper(Duration retention, MutableClock clock) {
		return new WorkspaceSweeper(properties(retention), runs, clock);
	}

	private Path workspace(UUID snapshotId, Instant modified) throws IOException {
		Path dir = root.resolve(snapshotId.toString());
		Files.createDirectories(dir.resolve("src"));
		Files.writeString(dir.resolve("src/App.java"), "class App {}");
		Files.setLastModifiedTime(dir, FileTime.from(modified));
		return dir;
	}

	private UUID snapshotWithRun(String name, RunStatus status) {
		TrackedRepository repository = repositories
			.save(new TrackedRepository("o", name, "https://github.com/o/" + name));
		RepositorySnapshot snapshot = snapshots.save(new RepositorySnapshot(repository, "4".repeat(40)));
		runs.save(new AnalysisRun(snapshot, status));
		return snapshot.getId();
	}

	@Test
	void sweepRemovesOrphansButKeepsActiveWorkspacesAndUnrelatedFiles() throws IOException {
		UUID active = snapshotWithRun("active", RunStatus.ANALYZING);
		UUID queued = snapshotWithRun("queued", RunStatus.QUEUED);
		UUID finished = snapshotWithRun("finished", RunStatus.COMPLETED);
		UUID noRunAtAll = UUID.randomUUID();
		Path activeDir = workspace(active, NOW);
		Path queuedDir = workspace(queued, NOW);
		Path finishedDir = workspace(finished, NOW);
		Path orphanDir = workspace(noRunAtAll, NOW);
		Path notAWorkspace = Files.createDirectories(root.resolve("my-notes"));
		Path stray = Files.writeString(root.resolve("readme.txt"), "keep me");

		int removed = sweeper(Duration.ZERO, new MutableClock(NOW)).sweepOrphans();

		assertThat(removed).isEqualTo(2);
		assertThat(activeDir).exists();
		assertThat(queuedDir).exists();
		assertThat(finishedDir).doesNotExist();
		assertThat(orphanDir).doesNotExist();
		assertThat(notAWorkspace).exists();
		assertThat(stray).exists();
	}

	@Test
	void startupSweepRemovesOrphansLeftByACrash() throws IOException {
		Path orphan = workspace(UUID.randomUUID(), NOW);

		sweeper(Duration.ZERO, new MutableClock(NOW)).sweepOnStartup();

		assertThat(orphan).doesNotExist();
	}

	@Test
	void retentionKeepsRecentWorkspacesUntilTheyAgeOut() throws IOException {
		MutableClock clock = new MutableClock(NOW);
		WorkspaceSweeper sweeper = sweeper(Duration.ofHours(1), clock);
		Path recent = workspace(UUID.randomUUID(), NOW.minus(Duration.ofMinutes(10)));
		Path old = workspace(UUID.randomUUID(), NOW.minus(Duration.ofHours(3)));

		assertThat(sweeper.sweepOrphans()).isEqualTo(1);
		assertThat(recent).exists();
		assertThat(old).doesNotExist();

		clock.advance(Duration.ofHours(2));

		assertThat(sweeper.sweepOrphans()).isEqualTo(1);
		assertThat(recent).doesNotExist();
	}

	@Test
	void staleDownloadsAreRemovedButFreshOnesAreKept() throws IOException {
		Path downloads = Files.createDirectories(root.resolve("_downloads"));
		Path stale = Files.writeString(downloads.resolve("old.tar.gz"), "x");
		Path fresh = Files.writeString(downloads.resolve("new.tar.gz"), "x");
		Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofHours(5))));
		Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(Duration.ofMinutes(5))));

		sweeper(Duration.ZERO, new MutableClock(NOW)).sweepOrphans();

		assertThat(stale).doesNotExist();
		assertThat(fresh).exists();
	}

	@Test
	void sweepingAMissingRootIsANoOp() {
		WorkspaceProperties missing = new WorkspaceProperties(root.resolve("does-not-exist"), Duration.ZERO,
				Duration.ofHours(1));

		assertThat(new WorkspaceSweeper(missing, runs, new MutableClock(NOW)).sweepOrphans()).isZero();
	}

	@Test
	void releaseDeletesImmediatelyWithZeroRetentionAndKeepsWithRetention() throws IOException {
		UUID snapshot = UUID.randomUUID();
		Path dir = workspace(snapshot, NOW);

		new WorkspaceManager(properties(Duration.ofHours(1)), new SafeTarExtractor()).releaseAfterRun(snapshot);
		assertThat(dir).exists();

		new WorkspaceManager(properties(Duration.ZERO), new SafeTarExtractor()).releaseAfterRun(snapshot);
		assertThat(dir).doesNotExist();
	}

	@Test
	void deletingAWorkspaceDoesNotFollowSymbolicLinksOutOfIt() throws IOException {
		Path outside = Files.createDirectories(root.resolve("outside-dir"));
		Path precious = Files.writeString(outside.resolve("precious.txt"), "do not delete");
		UUID snapshot = UUID.randomUUID();
		Path dir = workspace(snapshot, NOW);
		try {
			Files.createSymbolicLink(dir.resolve("link-to-outside"), outside);
		}
		catch (IOException | UnsupportedOperationException ex) {
			org.junit.jupiter.api.Assumptions.abort("Symbolic links are not available here");
		}

		new WorkspaceManager(properties(Duration.ZERO), new SafeTarExtractor()).delete(snapshot);

		assertThat(dir).doesNotExist();
		assertThat(precious).exists();
	}

	@Test
	void aFailedExtractionLeavesNoWorkspaceBehind() throws IOException {
		UUID snapshot = UUID.randomUUID();
		Path garbage = Files.writeString(root.resolve("garbage.tar.gz"), "not a tarball");
		WorkspaceManager manager = new WorkspaceManager(properties(Duration.ZERO), new SafeTarExtractor());

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> manager.extract(garbage, snapshot,
					new ExtractionLimits(1000, 1_000_000, 10, 100, java.util.List.of(), java.util.List.of())))
			.isInstanceOf(IOException.class);

		assertThat(manager.exists(snapshot)).isFalse();
	}

}
