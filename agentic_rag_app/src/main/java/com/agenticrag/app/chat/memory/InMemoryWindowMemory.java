package com.agenticrag.app.chat.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class InMemoryWindowMemory implements ChatMemory {
	private final int maxMessages;
	private final Map<String, Deque<ChatMessage>> messagesBySession = new ConcurrentHashMap<>();

	public InMemoryWindowMemory(WindowMemoryProperties properties) {
		int configured = properties != null ? properties.getMaxMessages() : 40;
		this.maxMessages = configured > 0 ? configured : 40;
	}

	@Override
	public void append(String sessionId, ChatMessage message) {
		if (sessionId == null || sessionId.trim().isEmpty() || message == null) {
			return;
		}
		String key = sessionId.trim();
		Deque<ChatMessage> deque = messagesBySession.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (deque) {
			deque.addLast(message);
			while (deque.size() > maxMessages) {
				deque.removeFirst();
			}
		}
	}

	@Override
	public List<ChatMessage> getMessages(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return new ArrayList<>();
		}
		Deque<ChatMessage> deque = messagesBySession.get(sessionId.trim());
		if (deque == null) {
			return new ArrayList<>();
		}
		synchronized (deque) {
			return new ArrayList<>(deque);
		}
	}

	@Override
	public List<String> getContents(String sessionId) {
		return getMessages(sessionId).stream()
			.map(ChatMessage::getContent)
			.collect(Collectors.toList());
	}

	@Override
	public void clear(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return;
		}
		messagesBySession.remove(sessionId.trim());
	}
}

