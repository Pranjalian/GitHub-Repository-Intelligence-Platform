package com.repointelligence.rag.qa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	/** A conversation only continues within its own repository and snapshot. */
	Optional<Conversation> findByIdAndRepositoryIdAndSnapshotId(UUID id, UUID repositoryId, UUID snapshotId);

}
