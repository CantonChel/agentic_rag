package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeRepository extends JpaRepository<KnowledgeEntity, String> {
	@Query("select k.knowledgeBaseId, count(k.id) from KnowledgeEntity k group by k.knowledgeBaseId order by k.knowledgeBaseId asc")
	List<Object[]> summarizeByKnowledgeBase();

	@Query(
		"select k.id, k.knowledgeBaseId, k.fileName, k.fileType, k.fileSize, k.parseStatus, k.enableStatus, k.createdAt, k.updatedAt "
			+ "from KnowledgeEntity k where k.knowledgeBaseId = :knowledgeBaseId order by k.createdAt desc"
	)
	List<Object[]> listDocumentRows(@Param("knowledgeBaseId") String knowledgeBaseId);
}
