package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class InMemorySessionContextManager implements ContextManager {
	private final SessionContextProperties props;
	private final SessionContextBudgetEvaluator budgetEvaluator;
	private final SessionContextSnapshotStore snapshotStore;
	private final SlidingWindowCompressionStrategy compressionStrategy = new SlidingWindowCompressionStrategy();
	private final Map<String, List<ChatMessage>> contextsBySessionId = new ConcurrentHashMap<>();

	public InMemorySessionContextManager(SessionContextProperties props, TokenCounter tokenCounter) {
		this(props, tokenCounter, null);
	}

	@Autowired
	public InMemorySessionContextManager(
		SessionContextProperties props,
		TokenCounter tokenCounter,
		SessionContextSnapshotStore snapshotStore
	) {
		this.props = props;
		this.budgetEvaluator = new SessionContextBudgetEvaluator(props, tokenCounter);
		this.snapshotStore = snapshotStore;
	}

	@Override
	public List<ChatMessage> getContext(String sessionId) {
		String sid = normalize(sessionId);
		List<ChatMessage> list = contextsBySessionId.get(sid);
		if (list == null) {
			list = snapshotStore != null ? snapshotStore.loadSnapshot(sid) : null;
			if (list == null || list.isEmpty()) {
				return new ArrayList<>();
			}
			List<ChatMessage> normalized = immutableCopy(list);
			contextsBySessionId.put(sid, normalized);
			return new ArrayList<>(normalized);
		}
		return new ArrayList<>(list);
	}

	@Override
	public String getSystemPrompt(String sessionId) {
		List<ChatMessage> list = getContext(sessionId);
		if (list == null || list.isEmpty()) {
			return "";
		}
		ChatMessage first = list.get(0);
		if (first != null && first.getType() == ChatMessageType.SYSTEM) {
			return first.getContent() != null ? first.getContent() : "";
		}
		return "";
	}

	@Override
	public void ensureSystemPrompt(String sessionId, String systemPrompt) {
		String sid = normalize(sessionId);
		List<ChatMessage> list = mutableCopy(getContext(sid));
		if (!list.isEmpty()) {
			ChatMessage first = list.get(0);
			if (first != null && first.getType() == ChatMessageType.SYSTEM) {
				String incoming = systemPrompt != null ? systemPrompt : "";
				String existing = first.getContent() != null ? first.getContent() : "";
				if (!existing.equals(incoming)) {
					list.set(0, new SystemMessage(incoming));
					storeContextSnapshot(sid, list);
				}
				return;
			}
		}
		list.add(0, new SystemMessage(systemPrompt != null ? systemPrompt : ""));
		storeContextSnapshot(sid, list);
	}

	@Override
	public void addMessage(String sessionId, ChatMessage message) {
		addMessage(sessionId, message, SessionContextAppendOptions.defaults());
	}

	@Override
	public void addMessage(String sessionId, ChatMessage message, SessionContextAppendOptions options) {
		if (message == null) {
			return;
		}

		ChatMessageType type = message.getType();
		if (type == ChatMessageType.SYSTEM) {
			return;
		}

		String sid = normalize(sessionId);
		List<ChatMessage> list = mutableCopy(getContext(sid));
		list.add(message);

		if (budgetEvaluator.exceedsStorageBudget(list)) {
			// Stage 3 keeps overflow trimming only as a final session-store capacity guard.
			list = compressionStrategy.compress(list, props != null ? props.getKeepLastMessages() : 20);
		}
		storeContextSnapshot(sid, list);
	}

	@Override
	public void clear(String sessionId) {
		String sid = normalize(sessionId);
		contextsBySessionId.remove(sid);
		if (snapshotStore != null) {
			snapshotStore.deleteSnapshot(sid);
		}
	}

	@Override
	public void replaceContext(String sessionId, List<ChatMessage> messages, SessionContextAppendOptions options) {
		String sid = normalize(sessionId);
		List<ChatMessage> normalized = normalizeReplacement(messages);
		if (normalized.isEmpty()) {
			clear(sid);
			return;
		}
		if (budgetEvaluator.exceedsStorageBudget(normalized)) {
			normalized = compressionStrategy.compress(normalized, props != null ? props.getKeepLastMessages() : 20);
		}
		storeContextSnapshot(sid, normalized);
	}

	private String normalize(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return "default";
		}
		return sessionId.trim();
	}

	private List<ChatMessage> normalizeReplacement(List<ChatMessage> messages) {
		List<ChatMessage> normalized = new ArrayList<>();
		if (messages == null) {
			return normalized;
		}
		for (ChatMessage message : messages) {
			if (message == null || message.getType() == null) {
				continue;
			}
			if (message.getType() == ChatMessageType.SYSTEM) {
				if (!normalized.isEmpty() && normalized.get(0).getType() == ChatMessageType.SYSTEM) {
					normalized.set(0, message);
				} else {
					normalized.add(0, message);
				}
				continue;
			}
			normalized.add(message);
		}
		return normalized;
	}

	private void storeContextSnapshot(String sessionId, List<ChatMessage> messages) {
		List<ChatMessage> snapshot = immutableCopy(messages);
		contextsBySessionId.put(sessionId, snapshot);
		if (snapshotStore != null) {
			snapshotStore.replaceSnapshot(sessionId, snapshot);
		}
	}

	private List<ChatMessage> mutableCopy(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(messages);
	}

	private List<ChatMessage> immutableCopy(List<ChatMessage> messages) {
		return List.copyOf(mutableCopy(messages));
	}
}
