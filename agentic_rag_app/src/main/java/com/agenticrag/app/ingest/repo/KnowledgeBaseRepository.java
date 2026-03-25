package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.KnowledgeBaseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
	List<KnowledgeBaseEntity> findAllByOrderByUpdatedAtDesc();
}
