package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.session.SessionScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionContextSnapshotStore {
	private final SessionContextMessageRepository repo;
	private final ObjectMapper objectMapper;

	public SessionContextSnapshotStore(SessionContextMessageRepository repo, ObjectMapper objectMapper) {
		this.repo = repo;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public List<ChatMessage> loadSnapshot(String scopedSessionId) {
		List<SessionContextMessageEntity> rows = repo.findByScopedSessionIdOrderByMessageIndexAsc(normalize(scopedSessionId));
		List<ChatMessage> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (SessionContextMessageEntity row : rows) {
			ChatMessage message = toMessage(row);
			if (message != null) {
				out.add(message);
			}
		}
		return out;
	}

	@Transactional
	public void replaceSnapshot(String scopedSessionId, List<ChatMessage> messages) {
		String normalizedScopedSessionId = normalize(scopedSessionId);
		repo.deleteByScopedSessionId(normalizedScopedSessionId);
		repo.flush();
		if (messages == null || messages.isEmpty()) {
			return;
		}

		String userId = SessionScope.userIdFromScopedSessionId(normalizedScopedSessionId);
		String sessionId = SessionScope.sessionIdFromScopedSessionId(normalizedScopedSessionId);
		Instant now = Instant.now();
		List<SessionContextMessageEntity> rows = new ArrayList<>();
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage message = messages.get(i);
			if (message == null || message.getType() == null) {
				continue;
			}
			SessionContextMessageEntity row = new SessionContextMessageEntity();
			row.setUserId(userId);
			row.setSessionId(sessionId);
			row.setScopedSessionId(normalizedScopedSessionId);
			row.setMessageIndex(i);
			row.setType(message.getType().name());
			row.setContent(serializeContent(message));
			row.setCreatedAt(now);
			row.setUpdatedAt(now);
			rows.add(row);
		}
		if (!rows.isEmpty()) {
			repo.saveAll(rows);
			repo.flush();
		}
	}

	@Transactional
	public void deleteSnapshot(String scopedSessionId) {
		repo.deleteByScopedSessionId(normalize(scopedSessionId));
	}

	@Transactional(readOnly = true)
	public List<SessionContextSnapshotSummary> listUserSnapshots(String userId) {
		String normalizedUserId = SessionScope.normalizeUserId(userId);
		List<SessionContextMessageRepository.SessionContextSnapshotSummaryView> rows = repo.fetchUserSnapshotSummaries(normalizedUserId);
		List<SessionContextSnapshotSummary> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (SessionContextMessageRepository.SessionContextSnapshotSummaryView row : rows) {
			if (row == null || row.getScopedSessionId() == null || row.getScopedSessionId().trim().isEmpty()) {
				continue;
			}
			out.add(
				new SessionContextSnapshotSummary(
					row.getUserId(),
					row.getSessionId(),
					row.getScopedSessionId(),
					row.getMessageCount(),
					row.getUpdatedAt()
				)
			);
		}
		return out;
	}

	private ChatMessage toMessage(SessionContextMessageEntity row) {
		if (row == null || row.getType() == null || row.getType().trim().isEmpty()) {
			return null;
		}
		ChatMessageType type;
		try {
			type = ChatMessageType.valueOf(row.getType());
		} catch (IllegalArgumentException ignored) {
			return null;
		}
		String content = row.getContent();
		switch (type) {
			case SYSTEM:
				return new SystemMessage(content);
			case USER:
				return new UserMessage(content);
			case ASSISTANT:
				return new AssistantMessage(content);
			case THINKING:
				return new ThinkingMessage(content);
			case TOOL_CALL:
				return new ToolCallMessage(readToolCalls(content));
			case TOOL_RESULT:
				ToolResultPayload payload = readToolResultPayload(content);
				return new ToolResultMessage(
					payload.getToolName(),
					payload.getToolCallId(),
					payload.isSuccess(),
					payload.getOutput(),
					payload.getError()
				);
			default:
				return null;
		}
	}

	private String serializeContent(ChatMessage message) {
		if (message == null || message.getType() == null) {
			return null;
		}
		if (message instanceof ToolCallMessage) {
			return writeJson(((ToolCallMessage) message).getToolCalls());
		}
		if (message instanceof ToolResultMessage) {
			ToolResultMessage toolResultMessage = (ToolResultMessage) message;
			ToolResultPayload payload = new ToolResultPayload(
				toolResultMessage.getToolName(),
				toolResultMessage.getToolCallId(),
				toolResultMessage.isSuccess(),
				toolResultMessage.getOutput(),
				toolResultMessage.getError()
			);
			return writeJson(payload);
		}
		return message.getContent();
	}

	private List<LlmToolCall> readToolCalls(String value) {
		if (value == null || value.trim().isEmpty()) {
			return Collections.emptyList();
		}
		try {
			return objectMapper.readValue(
				value,
				objectMapper.getTypeFactory().constructCollectionType(List.class, LlmToolCall.class)
			);
		} catch (Exception ignored) {
			return Collections.emptyList();
		}
	}

	private ToolResultPayload readToolResultPayload(String value) {
		if (value == null || value.trim().isEmpty()) {
			return new ToolResultPayload(null, null, false, null, null);
		}
		try {
			return objectMapper.readValue(value, ToolResultPayload.class);
		} catch (Exception ignored) {
			return new ToolResultPayload(null, null, false, null, value);
		}
	}

	private String writeJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception ignored) {
			return value.toString();
		}
	}

	private String normalize(String scopedSessionId) {
		if (scopedSessionId == null || scopedSessionId.trim().isEmpty()) {
			return "default";
		}
		return scopedSessionId.trim();
	}

	public static class SessionContextSnapshotSummary {
		private final String userId;
		private final String sessionId;
		private final String scopedSessionId;
		private final long messageCount;
		private final Instant updatedAt;

		public SessionContextSnapshotSummary(
			String userId,
			String sessionId,
			String scopedSessionId,
			long messageCount,
			Instant updatedAt
		) {
			this.userId = userId;
			this.sessionId = sessionId;
			this.scopedSessionId = scopedSessionId;
			this.messageCount = messageCount;
			this.updatedAt = updatedAt;
		}

		public String getUserId() {
			return userId;
		}

		public String getSessionId() {
			return sessionId;
		}

		public String getScopedSessionId() {
			return scopedSessionId;
		}

		public long getMessageCount() {
			return messageCount;
		}

		public Instant getUpdatedAt() {
			return updatedAt;
		}
	}

	public static class ToolResultPayload {
		private String toolName;
		private String toolCallId;
		private boolean success;
		private String output;
		private String error;

		public ToolResultPayload() {
		}

		public ToolResultPayload(String toolName, String toolCallId, boolean success, String output, String error) {
			this.toolName = toolName;
			this.toolCallId = toolCallId;
			this.success = success;
			this.output = output;
			this.error = error;
		}

		public String getToolName() {
			return toolName;
		}

		public void setToolName(String toolName) {
			this.toolName = toolName;
		}

		public String getToolCallId() {
			return toolCallId;
		}

		public void setToolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
		}

		public boolean isSuccess() {
			return success;
		}

		public void setSuccess(boolean success) {
			this.success = success;
		}

		public String getOutput() {
			return output;
		}

		public void setOutput(String output) {
			this.output = output;
		}

		public String getError() {
			return error;
		}

		public void setError(String error) {
			this.error = error;
		}
	}
}
