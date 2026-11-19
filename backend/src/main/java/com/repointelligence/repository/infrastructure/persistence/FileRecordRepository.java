package com.repointelligence.repository.infrastructure.persistence;

import com.repointelligence.repository.domain.FileRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {

	List<FileRecord> findBySnapshotId(UUID snapshotId);

	long countBySnapshotId(UUID snapshotId);

	boolean existsBySnapshotId(UUID snapshotId);

}
