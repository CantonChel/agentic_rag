package com.agenticrag.app.chat.context;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionContextMessageRepository extends JpaRepository<SessionContextMessageEntity, Long> {
	List<SessionContextMessageEntity> findByScopedSessionIdOrderByMessageIndexAsc(String scopedSessionId);

	long deleteByScopedSessionId(String scopedSessionId);

	@Query(
		"select m.scopedSessionId as scopedSessionId, m.userId as userId, m.sessionId as sessionId, " +
		"max(m.updatedAt) as updatedAt, count(m.id) as messageCount " +
		"from SessionContextMessageEntity m " +
		"where m.userId = :userId " +
		"group by m.scopedSessionId, m.userId, m.sessionId " +
		"order by max(m.updatedAt) desc, m.sessionId asc"
	)
	List<SessionContextSnapshotSummaryView> fetchUserSnapshotSummaries(@Param("userId") String userId);

	interface SessionContextSnapshotSummaryView {
		String getScopedSessionId();

		String getUserId();

		String getSessionId();

		Instant getUpdatedAt();

		long getMessageCount();
	}
}
