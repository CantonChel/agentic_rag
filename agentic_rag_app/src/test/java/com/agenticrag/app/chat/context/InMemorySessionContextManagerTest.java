package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InMemorySessionContextManagerTest {
	@Test
	void ensureSystemPromptReplacesExistingSystemPromptWhenChanged() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(1000);
		props.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);

		InMemorySessionContextManager manager = new InMemorySessionContextManager(props, tokenCounter, snapshotStore);

		manager.ensureSystemPrompt("s1", "base prompt");
		manager.ensureSystemPrompt("s1", "agent prompt");

		List<ChatMessage> context = manager.getContext("s1");
		Assertions.assertEquals(1, context.size());
		Assertions.assertEquals(ChatMessageType.SYSTEM, context.get(0).getType());
		Assertions.assertEquals("agent prompt", context.get(0).getContent());
		Mockito.verify(snapshotStore, Mockito.times(2)).replaceSnapshot(Mockito.eq("s1"), Mockito.anyList());
	}

	@Test
	void getContextLoadsSnapshotOnCacheMiss() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(1000);
		props.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);
		Mockito.when(snapshotStore.loadSnapshot("u1::s1"))
			.thenReturn(List.of(new com.agenticrag.app.chat.message.SystemMessage("SYSTEM"), new UserMessage("hello")));

		InMemorySessionContextManager manager = new InMemorySessionContextManager(props, tokenCounter, snapshotStore);

		List<ChatMessage> first = manager.getContext("u1::s1");
		List<ChatMessage> second = manager.getContext("u1::s1");

		Assertions.assertEquals(2, first.size());
		Assertions.assertEquals("SYSTEM", first.get(0).getContent());
		Assertions.assertEquals("hello", first.get(1).getContent());
		Assertions.assertEquals(first.size(), second.size());
		Mockito.verify(snapshotStore, Mockito.times(1)).loadSnapshot("u1::s1");
	}
}
