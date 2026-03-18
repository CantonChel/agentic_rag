package com.agenticrag.app.chat.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import java.util.List;

public interface ChatMemory {
	void append(String sessionId, ChatMessage message);

	List<ChatMessage> getMessages(String sessionId);

	List<String> getContents(String sessionId);

	void clear(String sessionId);
}

