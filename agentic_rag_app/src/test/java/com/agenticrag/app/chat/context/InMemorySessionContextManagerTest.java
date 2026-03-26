package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InMemorySessionContextManagerTest {
	@Test
	void ensureSystemPromptReplacesExistingSystemPromptWhenChanged() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(1000);
		props.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;

		InMemorySessionContextManager manager = new InMemorySessionContextManager(props, tokenCounter);

		manager.ensureSystemPrompt("s1", "base prompt");
		manager.ensureSystemPrompt("s1", "agent prompt");

		List<ChatMessage> context = manager.getContext("s1");
		Assertions.assertEquals(1, context.size());
		Assertions.assertEquals(ChatMessageType.SYSTEM, context.get(0).getType());
		Assertions.assertEquals("agent prompt", context.get(0).getContent());
	}
}
