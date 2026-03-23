package com.agenticrag.app.session;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.memory.MemoryFlushService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SessionManager {
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final MemoryFlushService memoryFlushService;
	private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

	public SessionManager(ContextManager contextManager, PersistentMessageStore persistentMessageStore) {
		this(contextManager, persistentMessageStore, null);
	}

	@Autowired
	public SessionManager(
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		MemoryFlushService memoryFlushService
	) {
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.memoryFlushService = memoryFlushService;
	}

	public ChatSession create(String userId) {
		String id = UUID.randomUUID().toString();
		ChatSession session = new ChatSession(id);
		sessions.put(SessionScope.scopedSessionId(userId, id), session);
		return session;
	}

	public boolean exists(String userId, String sessionId) {
		return sessions.containsKey(SessionScope.scopedSessionId(userId, sessionId));
	}

	public void delete(String userId, String sessionId) {
		String key = SessionScope.scopedSessionId(userId, sessionId);
		if (memoryFlushService != null) {
			memoryFlushService.flushOnSessionReset(
				key,
				contextManager.getContext(key),
				persistentMessageStore.list(key)
			);
		}
		sessions.remove(key);
		contextManager.clear(key);
		persistentMessageStore.clear(key);
	}

	public Collection<ChatSession> list(String userId) {
		String uid = SessionScope.normalizeUserId(userId);
		Collection<ChatSession> out = new ArrayList<>();
		for (Map.Entry<String, ChatSession> entry : sessions.entrySet()) {
			if (uid.equals(SessionScope.userIdFromScopedSessionId(entry.getKey()))) {
				out.add(entry.getValue());
			}
		}
		return out;
	}
}
