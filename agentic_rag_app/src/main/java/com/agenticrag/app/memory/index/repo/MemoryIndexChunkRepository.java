package com.agenticrag.app.memory.index.repo;

import com.agenticrag.app.memory.index.MemoryIndexChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryIndexChunkRepository extends JpaRepository<MemoryIndexChunkEntity, Long> {
	List<MemoryIndexChunkEntity> findByScopeTypeAndScopeId(String scopeType, String scopeId);

	void deleteByScopeTypeAndScopeId(String scopeType, String scopeId);

	void deleteByScopeTypeAndScopeIdAndPath(String scopeType, String scopeId, String path);
}
