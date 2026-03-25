package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeRepository extends JpaRepository<KnowledgeEntity, String> {
	long countByKnowledgeBaseId(String knowledgeBaseId);

	List<KnowledgeEntity> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);

	@Query("select k.id from KnowledgeEntity k where k.knowledgeBaseId = :knowledgeBaseId")
	List<String> listIdsByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

	@Modifying
	@Query("update KnowledgeEntity k set k.knowledgeBaseId = :toKnowledgeBaseId, k.updatedAt = :updatedAt where k.knowledgeBaseId = :fromKnowledgeBaseId")
	int moveKnowledgeBase(
		@Param("fromKnowledgeBaseId") String fromKnowledgeBaseId,
		@Param("toKnowledgeBaseId") String toKnowledgeBaseId,
		@Param("updatedAt") Instant updatedAt
	);

	@Query("select k.knowledgeBaseId, count(k.id) from KnowledgeEntity k group by k.knowledgeBaseId order by k.knowledgeBaseId asc")
	List<Object[]> summarizeByKnowledgeBase();

	@Query(
		"select k.id, k.knowledgeBaseId, k.fileName, k.fileType, k.fileSize, k.parseStatus, k.enableStatus, k.createdAt, k.updatedAt "
			+ "from KnowledgeEntity k where k.knowledgeBaseId = :knowledgeBaseId order by k.createdAt desc"
	)
	List<Object[]> listDocumentRows(@Param("knowledgeBaseId") String knowledgeBaseId);
}
