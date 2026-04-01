package com.agenticrag.app.memory.index.repo;

import com.agenticrag.app.memory.index.MemoryIndexMetaEntity;
import com.agenticrag.app.memory.index.MemoryIndexMetaId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryIndexMetaRepository extends JpaRepository<MemoryIndexMetaEntity, MemoryIndexMetaId> {
}
