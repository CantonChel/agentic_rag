package com.agenticrag.app.ingest.repo;

import com.agenticrag.app.ingest.entity.CallbackEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallbackEventRepository extends JpaRepository<CallbackEventEntity, Long> {
	boolean existsByEventId(String eventId);

	long deleteByJobIdIn(List<String> jobIds);
}
