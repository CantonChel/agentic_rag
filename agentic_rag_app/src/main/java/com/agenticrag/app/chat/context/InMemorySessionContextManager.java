package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.memory.MemoryFlushService;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class InMemorySessionContextManager implements ContextManager {
	private final SessionContextProperties props;
	private final TokenCounter tokenCounter;
	private final MemoryFlushService memoryFlushService;
	private final SlidingWindowCompressionStrategy compressionStrategy = new SlidingWindowCompressionStrategy();
	private final Map<String, List<ChatMessage>> contextsBySessionId = new ConcurrentHashMap<>();

	public InMemorySessionContextManager(SessionContextProperties props, TokenCounter tokenCounter) {
		this(props, tokenCounter, null);
	}

	@Autowired
	public InMemorySessionContextManager(
		SessionContextProperties props,
		TokenCounter tokenCounter,
		MemoryFlushService memoryFlushService
	) {
		this.props = props;
		this.tokenCounter = tokenCounter;
		this.memoryFlushService = memoryFlushService;
	}

	@Override
	public List<ChatMessage> getContext(String sessionId) {
		String sid = normalize(sessionId);
		List<ChatMessage> list = contextsBySessionId.get(sid);
		if (list == null) {
			return new ArrayList<>();
		}
		return new ArrayList<>(list);
	}

	@Override
	public String getSystemPrompt(String sessionId) {
		String sid = normalize(sessionId);
		List<ChatMessage> list = contextsBySessionId.get(sid);
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
		List<ChatMessage> list = contextsBySessionId.computeIfAbsent(sid, k -> Collections.synchronizedList(new ArrayList<>()));
		if (!list.isEmpty()) {
			ChatMessage first = list.get(0);
			if (first != null && first.getType() == ChatMessageType.SYSTEM) {
				String incoming = systemPrompt != null ? systemPrompt : "";
				String existing = first.getContent() != null ? first.getContent() : "";
				if (!existing.equals(incoming)) {
					list.set(0, new SystemMessage(incoming));
				}
				return;
			}
		}
		list.add(0, new SystemMessage(systemPrompt != null ? systemPrompt : ""));
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
		List<ChatMessage> list = contextsBySessionId.computeIfAbsent(sid, k -> Collections.synchronizedList(new ArrayList<>()));
		list.add(message);

		SessionContextAppendOptions effectiveOptions = options != null
			? options
			: SessionContextAppendOptions.defaults();
		int maxTokens = props != null && props.getMaxTokens() > 0 ? props.getMaxTokens() : 20000;
		int tokens = countTokens(list);
		int maxBytes = props != null ? props.getMaxBytes() : 0;
		int bytes = countBytes(list);
		boolean tokenOverflow = tokens > maxTokens;
		boolean bytesOverflow = maxBytes > 0 && bytes > maxBytes;
		if (!tokenOverflow && !bytesOverflow) {
			return;
		}
		if (memoryFlushService != null && effectiveOptions.isAllowPreCompactionFlush()) {
			memoryFlushService.flushPreCompaction(sid, list);
		}

		// Stage 1 keeps overflow trimming as a transitional session-context capacity guard.
		// Formal preflight compaction will be introduced separately in a later stage.
		List<ChatMessage> compressed = compressionStrategy.compress(list, props != null ? props.getKeepLastMessages() : 20);
		contextsBySessionId.put(sid, Collections.synchronizedList(compressed));
	}

	@Override
	public void clear(String sessionId) {
		contextsBySessionId.remove(normalize(sessionId));
	}

	private int countTokens(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}
		int tokens = 0;
		for (ChatMessage m : messages) {
			if (m == null) {
				continue;
			}
			String c = m.getContent();
			if (c == null || c.isEmpty()) {
				continue;
			}
			tokens += tokenCounter != null ? tokenCounter.count(c) : c.length();
		}
		return tokens;
	}

	private int countBytes(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}
		int bytes = 0;
		for (ChatMessage m : messages) {
			if (m == null) {
				continue;
			}
			String c = m.getContent();
			if (c == null || c.isEmpty()) {
				continue;
			}
			bytes += c.getBytes(StandardCharsets.UTF_8).length;
		}
		return bytes;
	}

	private String normalize(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return "default";
		}
		return sessionId.trim();
	}
}
