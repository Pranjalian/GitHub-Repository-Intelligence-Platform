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

	/** Startup recovery: ingestion interrupted by a restart did not finish; a later run starts it again. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RepositorySnapshot s set s.ingestionStatus = com.repointelligence.common.model.IngestionStatus.FAILED, "
			+ "s.ingestionError = :reason where s.ingestionStatus = com.repointelligence.common.model.IngestionStatus.INGESTING")
	int failInterruptedIngestions(@Param("reason") String reason);

}
