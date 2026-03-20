package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeRepository extends JpaRepository<KnowledgeEntity, String> {
	List<KnowledgeEntity> findAllByOrderByKnowledgeBaseIdAscCreatedAtDesc();

	List<KnowledgeEntity> findByKnowledgeBaseIdOrderByCreatedAtDesc(String knowledgeBaseId);
}
