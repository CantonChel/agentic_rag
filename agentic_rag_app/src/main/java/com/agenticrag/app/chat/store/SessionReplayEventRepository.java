package com.agenticrag.app.chat.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionReplayEventRepository extends JpaRepository<SessionReplayEventEntity, Long> {
	List<SessionReplayEventEntity> findBySessionIdOrderByEventTsAscSequenceIdAscIdAsc(String sessionId);

	long deleteBySessionId(String sessionId);

	@Query(
		"select e.sessionId as sessionId, max(e.eventTs) as lastEventTs, count(e.id) as eventCount " +
		"from SessionReplayEventEntity e group by e.sessionId"
	)
	List<SessionReplayStatsView> fetchSessionStats();

	interface SessionReplayStatsView {
		String getSessionId();

		Long getLastEventTs();

		long getEventCount();
	}
}
