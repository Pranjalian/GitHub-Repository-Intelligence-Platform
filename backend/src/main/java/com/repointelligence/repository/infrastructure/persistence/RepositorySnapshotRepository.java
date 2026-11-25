package com.repointelligence.repository.infrastructure.persistence;

import com.repointelligence.repository.domain.RepositorySnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorySnapshotRepository extends JpaRepository<RepositorySnapshot, UUID> {

	Optional<RepositorySnapshot> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

	/** Used to verify that a snapshot belongs to the repository in the request path. */
	Optional<RepositorySnapshot> findByIdAndRepositoryId(UUID id, UUID repositoryId);

	/** Snapshots of a repository, newest first (pass a {@code Pageable} for paging). */
	Page<RepositorySnapshot> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId, Pageable pageable);

	Optional<RepositorySnapshot> findFirstByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

	/** The newest snapshot of each given repository in one query (dashboard rows; no N+1). */
	@Query("""
			select s from RepositorySnapshot s
			where s.repository.id in :repositoryIds
			  and s.createdAt = (select max(s2.createdAt) from RepositorySnapshot s2 where s2.repository.id = s.repository.id)
			""")
	java.util.List<RepositorySnapshot> findLatestOfRepositories(
			@Param("repositoryIds") java.util.Collection<UUID> repositoryIds);

	/** Startup recovery: ingestion interrupted by a restart did not finish; a later run starts it again. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RepositorySnapshot s set s.ingestionStatus = com.repointelligence.common.model.IngestionStatus.FAILED, "
			+ "s.ingestionError = :reason where s.ingestionStatus = com.repointelligence.common.model.IngestionStatus.INGESTING")
	int failInterruptedIngestions(@Param("reason") String reason);

}
