package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LocalExecutionContextRecorder {
	private final Map<String, LocalExecutionContextSnapshot> latestBySessionId = new ConcurrentHashMap<>();

	public void record(String sessionId, String systemPrompt, List<ChatMessage> localContext, int iteration) {
		String sid = normalize(sessionId);
		List<ChatMessage> out = new ArrayList<>();
		out.add(new SystemMessage(systemPrompt != null ? systemPrompt : ""));
		if (localContext != null) {
			out.addAll(localContext);
		}
		latestBySessionId.put(sid, new LocalExecutionContextSnapshot(sid, iteration, Instant.now(), out));
	}

	public LocalExecutionContextSnapshot getLatest(String sessionId) {
		return latestBySessionId.get(normalize(sessionId));
	}

	private String normalize(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return "default";
		}
		return sessionId.trim();
	}

	public static class LocalExecutionContextSnapshot {
		private final String sessionId;
		private final int iteration;
		private final Instant recordedAt;
		private final List<ChatMessage> messages;

		public LocalExecutionContextSnapshot(String sessionId, int iteration, Instant recordedAt, List<ChatMessage> messages) {
			this.sessionId = sessionId;
			this.iteration = iteration;
			this.recordedAt = recordedAt;
			this.messages = messages;
		}

		public String getSessionId() {
			return sessionId;
		}

		public int getIteration() {
			return iteration;
		}

		public Instant getRecordedAt() {
			return recordedAt;
		}

		public List<ChatMessage> getMessages() {
			return messages;
		}
	}
}

