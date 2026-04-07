package com.agenticrag.app.memory.audit.repo;

import com.agenticrag.app.memory.audit.MemoryFactOperationLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryFactOperationLogRepository extends JpaRepository<MemoryFactOperationLogEntity, Long> {
	List<MemoryFactOperationLogEntity> findByUserIdAndFilePathOrderByCreatedAtDescIdDesc(String userId, String filePath);
}
