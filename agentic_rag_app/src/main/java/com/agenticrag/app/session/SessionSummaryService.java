package com.agenticrag.app.session;

import com.agenticrag.app.chat.store.PersistentMessageStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SessionSummaryService {
	private final SessionManager sessionManager;
	private final PersistentMessageStore persistentMessageStore;

	public SessionSummaryService(SessionManager sessionManager, PersistentMessageStore persistentMessageStore) {
		this.sessionManager = sessionManager;
		this.persistentMessageStore = persistentMessageStore;
	}

	public List<SessionSummary> listForUser(String userId) {
		String uid = SessionScope.normalizeUserId(userId);
		Map<String, ChatSession> inMemorySessions = sessionManager.list(uid)
			.stream()
			.collect(Collectors.toMap(ChatSession::getId, session -> session, (left, right) -> left));
		Map<String, PersistentMessageStore.SessionMessageStats> messageStatsBySessionId = persistentMessageStore.listSessionStats()
			.stream()
			.filter(stats -> uid.equals(SessionScope.userIdFromScopedSessionId(stats.getSessionId())))
			.collect(
				Collectors.toMap(
					stats -> SessionScope.sessionIdFromScopedSessionId(stats.getSessionId()),
					stats -> stats,
					(left, right) -> left
				)
			);

		LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
		sessionIds.addAll(inMemorySessions.keySet());
		sessionIds.addAll(messageStatsBySessionId.keySet());

		List<SessionSummary> out = new ArrayList<>();
		for (String sessionId : sessionIds) {
			ChatSession session = inMemorySessions.get(sessionId);
			PersistentMessageStore.SessionMessageStats stats = messageStatsBySessionId.get(sessionId);
			Instant createdAt = resolveCreatedAt(session, stats);
			Instant lastActiveAt = resolveLastActiveAt(createdAt, stats);
			boolean hasMessages = stats != null && stats.getMessageCount() > 0;
			out.add(new SessionSummary(sessionId, createdAt, lastActiveAt, hasMessages));
		}

		out.sort(
			Comparator.comparing(SessionSummary::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(SessionSummary::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(SessionSummary::getSessionId, Comparator.nullsLast(String::compareTo))
		);
		return out;
	}

	private Instant resolveCreatedAt(ChatSession session, PersistentMessageStore.SessionMessageStats stats) {
		if (session != null && session.getCreatedAt() != null) {
			return session.getCreatedAt();
		}
		if (stats != null && stats.getFirstMessageAt() != null) {
			return stats.getFirstMessageAt();
		}
		return Instant.EPOCH;
	}

	private Instant resolveLastActiveAt(Instant createdAt, PersistentMessageStore.SessionMessageStats stats) {
		if (stats != null && stats.getLastMessageAt() != null) {
			return stats.getLastMessageAt();
		}
		return createdAt;
	}

	public static class SessionSummary {
		private final String sessionId;
		private final Instant createdAt;
		private final Instant lastActiveAt;
		private final boolean hasMessages;

		public SessionSummary(String sessionId, Instant createdAt, Instant lastActiveAt, boolean hasMessages) {
			this.sessionId = sessionId;
			this.createdAt = createdAt;
			this.lastActiveAt = lastActiveAt;
			this.hasMessages = hasMessages;
		}

		public String getSessionId() {
			return sessionId;
		}

		public Instant getCreatedAt() {
			return createdAt;
		}

		public Instant getLastActiveAt() {
			return lastActiveAt;
		}

		public boolean isHasMessages() {
			return hasMessages;
		}
	}
}
