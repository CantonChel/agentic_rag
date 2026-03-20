package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<ChunkEntity, Long> {
	long deleteByKnowledgeId(String knowledgeId);

	List<ChunkEntity> findByKnowledgeIdOrderByChunkIndexAsc(String knowledgeId);
}
