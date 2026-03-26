package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.context.LocalExecutionContextRecorder;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.session.SessionScope;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions/{sessionId}/contexts")
public class ContextDebugController {
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final LocalExecutionContextRecorder localExecutionContextRecorder;
	private final SystemPromptManager systemPromptManager;

	public ContextDebugController(
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		LocalExecutionContextRecorder localExecutionContextRecorder,
		SystemPromptManager systemPromptManager
	) {
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.localExecutionContextRecorder = localExecutionContextRecorder;
		this.systemPromptManager = systemPromptManager;
	}

	@GetMapping("/session")
	public List<SessionController.ChatMessageView> sessionContext(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		String scopedSessionId = SessionScope.scopedSessionId(userId, sessionId);
		return contextManager.getContext(scopedSessionId).stream()
			.map(m -> new SessionController.ChatMessageView(m.getType().name(), m.getContent(), null, null, null, null, null))
			.collect(Collectors.toList());
	}

	@GetMapping("/local")
	public LocalExecutionContextView localContext(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		String scopedSessionId = SessionScope.scopedSessionId(userId, sessionId);
		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snap = localExecutionContextRecorder.getLatest(scopedSessionId);
		if (snap == null) {
			return new LocalExecutionContextView(sessionId, 0, null, java.util.Collections.emptyList());
		}
		List<SessionController.ChatMessageView> msgs = snap.getMessages().stream()
			.map(m -> new SessionController.ChatMessageView(m.getType().name(), m.getContent(), null, null, null, null, null))
			.collect(Collectors.toList());
		return new LocalExecutionContextView(
			SessionScope.sessionIdFromScopedSessionId(snap.getSessionId()),
			snap.getIteration(),
			snap.getRecordedAt().toString(),
			msgs
		);
	}

	@PostMapping("/seed")
	public SeedResult seed(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "count", defaultValue = "50") int count,
		@RequestParam(value = "chars", defaultValue = "200") int chars,
		@RequestParam(value = "mode", defaultValue = "LLM") SystemPromptMode mode
	) {
		String scopedSessionId = SessionScope.scopedSessionId(userId, sessionId);
		int c = Math.max(0, Math.min(count, 2000));
		int len = Math.max(1, Math.min(chars, 5000));
		String payload = repeat("中", len);

		String configuredSystemPrompt = systemPromptManager.build(new SystemPromptContext(LlmProvider.OPENAI, true, mode));
		contextManager.ensureSystemPrompt(scopedSessionId, configuredSystemPrompt);
		String systemPrompt = contextManager.getSystemPrompt(scopedSessionId);
		persistentMessageStore.ensureSystemPrompt(scopedSessionId, systemPrompt);

		for (int i = 0; i < c; i++) {
			ChatMessage u = new UserMessage("seed-" + i + ": " + payload);
			persistentMessageStore.append(scopedSessionId, u);
			contextManager.addMessage(scopedSessionId, u);
		}
		return new SeedResult(c, len);
	}

	private String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			sb.append(s);
		}
		return sb.toString();
	}

	public static class SeedResult {
		private final int count;
		private final int chars;

		public SeedResult(int count, int chars) {
			this.count = count;
			this.chars = chars;
		}

		public int getCount() {
			return count;
		}

		public int getChars() {
			return chars;
		}
	}

	public static class LocalExecutionContextView {
		private final String sessionId;
		private final int iteration;
		private final String recordedAt;
		private final List<SessionController.ChatMessageView> messages;

		public LocalExecutionContextView(String sessionId, int iteration, String recordedAt, List<SessionController.ChatMessageView> messages) {
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

		public String getRecordedAt() {
			return recordedAt;
		}

		public List<SessionController.ChatMessageView> getMessages() {
			return messages;
		}
	}
}
