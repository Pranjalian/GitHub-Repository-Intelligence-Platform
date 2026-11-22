package com.repointelligence.rag.qa;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

	/** Newest turns first (pass a page size to bound them). */
	List<ConversationMessage> findByConversationIdOrderBySeqDesc(UUID conversationId, Pageable pageable);

	List<ConversationMessage> findByConversationIdOrderBySeqAsc(UUID conversationId);

}
