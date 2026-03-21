package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmbeddingRepository extends JpaRepository<EmbeddingEntity, Long> {
	long deleteByKnowledgeId(String knowledgeId);

	List<EmbeddingEntity> findByChunkIdIn(List<String> chunkIds);

	@Query("select e.chunkId, e.knowledgeId, e.content from EmbeddingEntity e where e.chunkId in :chunkIds")
	List<Object[]> listChunkContentByChunkIds(@Param("chunkIds") List<String> chunkIds);
}
