package com.repointelligence.ai.infrastructure.persistence;

import com.repointelligence.ai.domain.AICacheEntry;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AICacheEntryRepository extends JpaRepository<AICacheEntry, String> {

	@Transactional
	@Modifying
	@Query("DELETE FROM AICacheEntry e WHERE e.expiresAt < :now")
	int deleteExpired(@Param("now") Instant now);

}
