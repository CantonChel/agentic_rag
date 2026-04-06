package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import java.util.List;

public interface ContextManager {
	List<ChatMessage> getContext(String sessionId);

	String getSystemPrompt(String sessionId);

	void ensureSystemPrompt(String sessionId, String systemPrompt);

	default void replaceContext(String sessionId, List<ChatMessage> messages) {
		replaceContext(sessionId, messages, SessionContextAppendOptions.defaults());
	}

	default void replaceContext(String sessionId, List<ChatMessage> messages, SessionContextAppendOptions options) {
		clear(sessionId);
		if (messages == null || messages.isEmpty()) {
			return;
		}
		for (ChatMessage message : messages) {
			if (message == null) {
				continue;
			}
			if (message.getType() == com.agenticrag.app.chat.message.ChatMessageType.SYSTEM) {
				ensureSystemPrompt(sessionId, message.getContent());
				continue;
			}
			addMessage(sessionId, message, options);
		}
	}

	default void addMessage(String sessionId, ChatMessage message) {
		addMessage(sessionId, message, SessionContextAppendOptions.defaults());
	}

	void addMessage(String sessionId, ChatMessage message, SessionContextAppendOptions options);

	void clear(String sessionId);
}
