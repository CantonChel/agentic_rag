package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.memory.MemoryFlushService;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InMemorySessionContextManagerOverflowTest {
	@Test
	void keepsSystemPromptAtIndexZeroWhenCompressing() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(200);
		props.setKeepLastMessages(5);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager mgr = new InMemorySessionContextManager(props, tokenCounter);

		String sid = "s1";
		mgr.ensureSystemPrompt(sid, "SYSTEM_PROMPT");

		for (int i = 0; i < 50; i++) {
			mgr.addMessage(sid, new UserMessage("m" + i + ":" + repeat("中", 30)));
		}

		List<ChatMessage> ctx = mgr.getContext(sid);
		Assertions.assertFalse(ctx.isEmpty());
		Assertions.assertEquals("SYSTEM", ctx.get(0).getType().name());
		Assertions.assertEquals("SYSTEM_PROMPT", ctx.get(0).getContent());
		Assertions.assertTrue(ctx.size() <= 1 + props.getKeepLastMessages());
	}

	private static String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			sb.append(s);
		}
		return sb.toString();
	}

	@Test
	void triggersPreCompactionFlushBeforeCompression() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(120);
		props.setKeepLastMessages(3);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		MemoryFlushService memoryFlushService = Mockito.mock(MemoryFlushService.class);
		InMemorySessionContextManager mgr = new InMemorySessionContextManager(props, tokenCounter, memoryFlushService);

		String sid = "u1::s1";
		mgr.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		for (int i = 0; i < 20; i++) {
			mgr.addMessage(sid, new UserMessage("m" + i + ":" + repeat("x", 30)));
		}

		Mockito.verify(memoryFlushService, Mockito.atLeastOnce())
			.flushPreCompaction(Mockito.eq(sid), Mockito.anyList());
	}
}
