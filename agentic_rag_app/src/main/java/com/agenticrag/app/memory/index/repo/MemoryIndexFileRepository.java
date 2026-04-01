package com.agenticrag.app.memory.index.repo;

import com.agenticrag.app.memory.index.MemoryIndexFileEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryIndexFileRepository extends JpaRepository<MemoryIndexFileEntity, Long> {
	List<MemoryIndexFileEntity> findByScopeTypeAndScopeId(String scopeType, String scopeId);

	Optional<MemoryIndexFileEntity> findByScopeTypeAndScopeIdAndPath(String scopeType, String scopeId, String path);

	void deleteByScopeTypeAndScopeId(String scopeType, String scopeId);

	void deleteByScopeTypeAndScopeIdAndPath(String scopeType, String scopeId, String path);
}
