package com.repointelligence.repository.infrastructure.persistence;

import com.repointelligence.repository.domain.RepositorySnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorySnapshotRepository extends JpaRepository<RepositorySnapshot, UUID> {

	Optional<RepositorySnapshot> findByRepositoryIdAndCommitSha(UUID repositoryId, String commitSha);

	/** Used to verify that a snapshot belongs to the repository in the request path. */
	Optional<RepositorySnapshot> findByIdAndRepositoryId(UUID id, UUID repositoryId);

	/** Snapshots of a repository, newest first (pass a {@code Pageable} for paging). */
	Page<RepositorySnapshot> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId, Pageable pageable);

	Optional<RepositorySnapshot> findFirstByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

}
