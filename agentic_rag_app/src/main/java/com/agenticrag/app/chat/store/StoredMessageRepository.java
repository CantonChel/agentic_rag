package com.agenticrag.app.chat.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoredMessageRepository extends JpaRepository<StoredMessageEntity, Long> {
	List<StoredMessageEntity> findBySessionIdOrderByIdAsc(String sessionId);

	boolean existsBySessionIdAndType(String sessionId, String type);

	long deleteBySessionId(String sessionId);

	@Query("select distinct s.sessionId from StoredMessageEntity s order by s.sessionId")
	List<String> findDistinctSessionIds();
}
