package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import java.util.List;

public interface ContextManager {
	List<ChatMessage> getContext(String sessionId);

	String getSystemPrompt(String sessionId);

	void ensureSystemPrompt(String sessionId, String systemPrompt);

	void addMessage(String sessionId, ChatMessage message);

	void clear(String sessionId);
}
