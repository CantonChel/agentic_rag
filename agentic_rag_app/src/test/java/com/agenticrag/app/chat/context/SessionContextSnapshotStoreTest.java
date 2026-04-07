package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionContextSnapshotStoreTest {
	@Autowired
	private SessionContextSnapshotStore snapshotStore;

	@Autowired
	private SessionContextMessageRepository repo;

	@BeforeEach
	void setUp() {
		repo.deleteAll();
	}

	@Test
	void replaceLoadListAndDeleteSnapshotShouldWork() {
		String scopedSessionId = "u1::s1";
		List<ChatMessage> messages = List.of(
			new SystemMessage("SYSTEM"),
			new UserMessage("hello"),
			new AssistantMessage("world"),
			new ToolResultMessage("memory_search", "call-1", true, "{\"hits\":1}", null)
		);

		snapshotStore.replaceSnapshot(scopedSessionId, messages);

		List<ChatMessage> restored = snapshotStore.loadSnapshot(scopedSessionId);
		List<SessionContextSnapshotStore.SessionContextSnapshotSummary> summaries = snapshotStore.listUserSnapshots("u1");

		Assertions.assertEquals(4, restored.size());
		Assertions.assertEquals("SYSTEM", restored.get(0).getContent());
		Assertions.assertEquals("hello", restored.get(1).getContent());
		Assertions.assertEquals("world", restored.get(2).getContent());
		Assertions.assertEquals("TOOL_RESULT", restored.get(3).getType().name());
		Assertions.assertEquals("{\"hits\":1}", restored.get(3).getContent());
		Assertions.assertEquals(1, summaries.size());
		Assertions.assertEquals("s1", summaries.get(0).getSessionId());
		Assertions.assertEquals(4, summaries.get(0).getMessageCount());

		snapshotStore.deleteSnapshot(scopedSessionId);
		Assertions.assertTrue(snapshotStore.loadSnapshot(scopedSessionId).isEmpty());
		Assertions.assertTrue(snapshotStore.listUserSnapshots("u1").isEmpty());
	}

	@Test
	void managerCanRestoreSnapshotAfterRestartLikeCacheMiss() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(1000);
		props.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		String scopedSessionId = "u1::recover";

		InMemorySessionContextManager writer = new InMemorySessionContextManager(props, tokenCounter, snapshotStore);
		writer.ensureSystemPrompt(scopedSessionId, "SYSTEM");
		writer.addMessage(scopedSessionId, new UserMessage("hi"));
		writer.addMessage(scopedSessionId, new AssistantMessage("hello"));

		InMemorySessionContextManager reader = new InMemorySessionContextManager(props, tokenCounter, snapshotStore);
		List<ChatMessage> restored = reader.getContext(scopedSessionId);

		Assertions.assertEquals(3, restored.size());
		Assertions.assertEquals("SYSTEM", restored.get(0).getContent());
		Assertions.assertEquals("hi", restored.get(1).getContent());
		Assertions.assertEquals("hello", restored.get(2).getContent());
	}
}
