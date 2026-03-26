package com.agenticrag.app.chat.store;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.llm.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentMessageStore {
	private final StoredMessageRepository repo;
	private final ObjectMapper objectMapper;

	public PersistentMessageStore(StoredMessageRepository repo, ObjectMapper objectMapper) {
		this.repo = repo;
		this.objectMapper = objectMapper;
	}

	public void append(String sessionId, ChatMessage message) {
		if (message == null) {
			return;
		}
		StoredMessageEntity e = new StoredMessageEntity();
		e.setSessionId(normalize(sessionId));
		e.setType(message.getType().name());
		e.setCreatedAt(Instant.now());

		if (message instanceof ToolResultMessage) {
			ToolResultMessage tr = (ToolResultMessage) message;
			e.setToolName(tr.getToolName());
			e.setToolCallId(tr.getToolCallId());
			e.setSuccess(tr.isSuccess());
			e.setContent(tr.getContent());
		} else if (message instanceof ToolCallMessage) {
			ToolCallMessage tc = (ToolCallMessage) message;
			e.setContent(serializeToolCalls(tc.getToolCalls()));
		} else {
			e.setContent(message.getContent());
		}

		repo.save(e);
	}

	public void ensureSystemPrompt(String sessionId, String systemPrompt) {
		String sid = normalize(sessionId);
		String incoming = systemPrompt != null ? systemPrompt : "";
		if (repo.existsBySessionIdAndType(sid, ChatMessageType.SYSTEM.name())) {
			List<StoredMessageEntity> messages = repo.findBySessionIdOrderByIdAsc(sid);
			for (StoredMessageEntity message : messages) {
				if (message == null || !ChatMessageType.SYSTEM.name().equals(message.getType())) {
					continue;
				}
				String existing = message.getContent() != null ? message.getContent() : "";
				if (!existing.equals(incoming)) {
					message.setContent(incoming);
					repo.save(message);
				}
				return;
			}
		}
		append(sid, new SystemMessage(incoming));
	}

	public List<StoredMessageEntity> list(String sessionId) {
		return repo.findBySessionIdOrderByIdAsc(normalize(sessionId));
	}

	public List<String> listSessionIds() {
		return repo.findDistinctSessionIds();
	}

	public List<SessionMessageStats> listSessionStats() {
		List<StoredMessageRepository.SessionMessageStatsView> rows = repo.fetchSessionStats();
		List<SessionMessageStats> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (StoredMessageRepository.SessionMessageStatsView row : rows) {
			if (row == null || row.getSessionId() == null || row.getSessionId().trim().isEmpty()) {
				continue;
			}
			out.add(
				new SessionMessageStats(
					row.getSessionId(),
					row.getFirstMessageAt(),
					row.getLastMessageAt(),
					row.getMessageCount()
				)
			);
		}
		return out;
	}

	@Transactional
	public void clear(String sessionId) {
		repo.deleteBySessionId(normalize(sessionId));
	}

	public List<LlmToolCall> parseToolCalls(String json) {
		if (json == null || json.trim().isEmpty()) {
			return new ArrayList<>();
		}
		try {
			java.lang.reflect.Type t = objectMapper.getTypeFactory().constructCollectionType(List.class, LlmToolCall.class);
			return objectMapper.readValue(json, objectMapper.getTypeFactory().constructType(t));
		} catch (Exception ignored) {
			return new ArrayList<>();
		}
	}

	private String serializeToolCalls(List<LlmToolCall> calls) {
		if (calls == null) {
			calls = Collections.emptyList();
		}
		try {
			return objectMapper.writeValueAsString(calls);
		} catch (Exception ignored) {
			return "[]";
		}
	}

	private String normalize(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return "default";
		}
		return sessionId.trim();
	}

	public static class SessionMessageStats {
		private final String sessionId;
		private final Instant firstMessageAt;
		private final Instant lastMessageAt;
		private final long messageCount;

		public SessionMessageStats(String sessionId, Instant firstMessageAt, Instant lastMessageAt, long messageCount) {
			this.sessionId = sessionId;
			this.firstMessageAt = firstMessageAt;
			this.lastMessageAt = lastMessageAt;
			this.messageCount = messageCount;
		}

		public String getSessionId() {
			return sessionId;
		}

		public Instant getFirstMessageAt() {
			return firstMessageAt;
		}

		public Instant getLastMessageAt() {
			return lastMessageAt;
		}

		public long getMessageCount() {
			return messageCount;
		}
	}
}
