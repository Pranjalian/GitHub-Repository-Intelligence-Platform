package com.repointelligence.repository.infrastructure.persistence;

import com.repointelligence.repository.domain.TrackedRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackedRepositoryRepository extends JpaRepository<TrackedRepository, UUID> {

	/** GitHub owner and repository names are case-insensitive (matches the unique index on lower()). */
	Optional<TrackedRepository> findByHostAndOwnerIgnoreCaseAndNameIgnoreCase(String host, String owner, String name);

}
