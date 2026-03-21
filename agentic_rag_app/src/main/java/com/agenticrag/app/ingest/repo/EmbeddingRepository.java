package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmbeddingRepository extends JpaRepository<EmbeddingEntity, Long> {
	long deleteByKnowledgeId(String knowledgeId);

	List<EmbeddingEntity> findByChunkIdIn(List<String> chunkIds);
}
