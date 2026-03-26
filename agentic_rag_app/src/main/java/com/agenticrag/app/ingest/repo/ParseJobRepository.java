package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParseJobRepository extends JpaRepository<ParseJobEntity, String> {
	Optional<ParseJobEntity> findByIdempotencyKey(String idempotencyKey);

	List<ParseJobEntity> findByKnowledgeId(String knowledgeId);

	long deleteByKnowledgeId(String knowledgeId);

	List<ParseJobEntity> findTop100ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(ParseJobStatus status, Instant now);

	List<ParseJobEntity> findTop100ByStatusInAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(Collection<ParseJobStatus> statuses, Instant leaseUntil);

	List<ParseJobEntity> findTop100ByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(ParseJobStatus status, Instant updatedAt);

	@Modifying
	@Query("update ParseJobEntity j set j.status=:toStatus, j.updatedAt=:now, j.leaseUntil=:leaseUntil, j.nextRetryAt=:nextRetryAt where j.id=:jobId and j.status in :fromStatuses")
	int transitionIfStatusIn(
		@Param("jobId") String jobId,
		@Param("fromStatuses") Collection<ParseJobStatus> fromStatuses,
		@Param("toStatus") ParseJobStatus toStatus,
		@Param("leaseUntil") Instant leaseUntil,
		@Param("nextRetryAt") Instant nextRetryAt,
		@Param("now") Instant now
	);
}
