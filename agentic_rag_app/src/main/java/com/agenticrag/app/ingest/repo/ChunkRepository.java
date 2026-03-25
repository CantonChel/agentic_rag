package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkRepository extends JpaRepository<ChunkEntity, Long> {
	long deleteByKnowledgeId(String knowledgeId);

	List<ChunkEntity> findByKnowledgeIdOrderByChunkIndexAsc(String knowledgeId);

	@Query("select c.chunkId from ChunkEntity c where c.knowledgeId = :knowledgeId")
	List<String> listChunkIdsByKnowledgeId(@Param("knowledgeId") String knowledgeId);
}
