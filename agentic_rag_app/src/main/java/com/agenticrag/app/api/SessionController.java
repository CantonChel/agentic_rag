package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.session.ChatSession;
import com.agenticrag.app.session.SessionScope;
import com.agenticrag.app.session.SessionManager;
import com.agenticrag.app.session.SessionSummaryService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
	private final SessionManager sessionManager;
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final SessionSummaryService sessionSummaryService;

	public SessionController(
		SessionManager sessionManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		SessionSummaryService sessionSummaryService
	) {
		this.sessionManager = sessionManager;
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.sessionSummaryService = sessionSummaryService;
	}

	@PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public CreateSessionResponse create(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		ChatSession session = sessionManager.create(userId);
		return new CreateSessionResponse(session.getId());
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<SessionSummaryResponse> list(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		return sessionSummaryService.listForUser(userId)
			.stream()
			.map(SessionSummaryResponse::new)
			.collect(java.util.stream.Collectors.toList());
	}

	@DeleteMapping("/{sessionId}")
	public void delete(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		sessionManager.delete(userId, sessionId);
	}

	@GetMapping(value = "/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ChatMessageView> messages(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "contentsOnly", defaultValue = "false") boolean contentsOnly
	) {
		String scopedSessionId = SessionScope.scopedSessionId(userId, sessionId);
		if (contentsOnly) {
			return contextManager.getContext(scopedSessionId).stream()
				.map(c -> new ChatMessageView(c.getType().name(), c.getContent(), null, null, null, null, null))
				.collect(Collectors.toList());
		}

		return persistentMessageStore.list(scopedSessionId).stream()
			.map(e -> toView(e, persistentMessageStore))
			.collect(Collectors.toList());
	}

	private static ChatMessageView toView(StoredMessageEntity e, PersistentMessageStore store) {
		if (e == null) {
			return new ChatMessageView("UNKNOWN", null, null, null, null, null, null);
		}

		if ("TOOL_CALL".equals(e.getType())) {
			List<LlmToolCall> calls = store.parseToolCalls(e.getContent());
			return new ChatMessageView(e.getType(), null, calls, null, null, null, null);
		}
		if ("TOOL_RESULT".equals(e.getType())) {
			return new ChatMessageView(
				e.getType(),
				e.getContent(),
				null,
				e.getToolName(),
				e.getToolCallId(),
				e.getSuccess(),
				e.getContent()
			);
		}
		return new ChatMessageView(e.getType(), e.getContent(), null, null, null, null, null);
	}

	public static class CreateSessionResponse {
		private final String sessionId;

		public CreateSessionResponse(String sessionId) {
			this.sessionId = sessionId;
		}

		public String getSessionId() {
			return sessionId;
		}
	}

	public static class SessionSummaryResponse {
		private final String sessionId;
		private final java.time.Instant createdAt;
		private final java.time.Instant lastActiveAt;
		private final boolean hasMessages;

		public SessionSummaryResponse(SessionSummaryService.SessionSummary summary) {
			this.sessionId = summary != null ? summary.getSessionId() : null;
			this.createdAt = summary != null ? summary.getCreatedAt() : null;
			this.lastActiveAt = summary != null ? summary.getLastActiveAt() : null;
			this.hasMessages = summary != null && summary.isHasMessages();
		}

		public String getSessionId() {
			return sessionId;
		}

		public java.time.Instant getCreatedAt() {
			return createdAt;
		}

		public java.time.Instant getLastActiveAt() {
			return lastActiveAt;
		}

		public boolean isHasMessages() {
			return hasMessages;
		}
	}

	public static class ChatMessageView {
		private final String type;
		private final String content;
		private final List<LlmToolCall> toolCalls;
		private final String toolName;
		private final String toolCallId;
		private final Boolean success;
		private final String result;

		public ChatMessageView(
			String type,
			String content,
			List<LlmToolCall> toolCalls,
			String toolName,
			String toolCallId,
			Boolean success,
			String result
		) {
			this.type = type;
			this.content = content;
			this.toolCalls = toolCalls;
			this.toolName = toolName;
			this.toolCallId = toolCallId;
			this.success = success;
			this.result = result;
		}

		public String getType() {
			return type;
		}

		public String getContent() {
			return content;
		}

		public List<LlmToolCall> getToolCalls() {
			return toolCalls;
		}

		public String getToolName() {
			return toolName;
		}

		public String getToolCallId() {
			return toolCallId;
		}

		public Boolean getSuccess() {
			return success;
		}

		public String getResult() {
			return result;
		}
	}
}
