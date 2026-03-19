package com.agenticrag.app.session;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class SessionManager {
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

	public SessionManager(ContextManager contextManager, PersistentMessageStore persistentMessageStore) {
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
	}

	public ChatSession create() {
		String id = UUID.randomUUID().toString();
		ChatSession session = new ChatSession(id);
		sessions.put(id, session);
		return session;
	}

	public boolean exists(String sessionId) {
		if (sessionId == null) {
			return false;
		}
		return sessions.containsKey(sessionId);
	}

	public void delete(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return;
		}
		String key = sessionId.trim();
		sessions.remove(key);
		contextManager.clear(key);
		persistentMessageStore.clear(key);
	}

	public Collection<ChatSession> list() {
		return new ArrayList<>(sessions.values());
	}
}
