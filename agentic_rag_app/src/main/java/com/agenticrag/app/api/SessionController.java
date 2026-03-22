package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.session.ChatSession;
import com.agenticrag.app.session.SessionManager;
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

	public SessionController(SessionManager sessionManager, ContextManager contextManager, PersistentMessageStore persistentMessageStore) {
		this.sessionManager = sessionManager;
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
	}

	@PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public CreateSessionResponse create() {
		ChatSession session = sessionManager.create();
		return new CreateSessionResponse(session.getId());
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<CreateSessionResponse> list() {
		java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
		sessionManager.list().forEach(s -> ids.add(s.getId()));
		persistentMessageStore.listSessionIds().forEach(ids::add);
		return ids.stream()
			.map(CreateSessionResponse::new)
			.collect(Collectors.toList());
	}

	@DeleteMapping("/{sessionId}")
	public void delete(@PathVariable("sessionId") String sessionId) {
		sessionManager.delete(sessionId);
	}

	@GetMapping(value = "/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ChatMessageView> messages(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "contentsOnly", defaultValue = "false") boolean contentsOnly
	) {
		if (contentsOnly) {
			return contextManager.getContext(sessionId).stream()
				.map(c -> new ChatMessageView(c.getType().name(), c.getContent(), null, null, null, null, null))
				.collect(Collectors.toList());
		}

		return persistentMessageStore.list(sessionId).stream()
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
