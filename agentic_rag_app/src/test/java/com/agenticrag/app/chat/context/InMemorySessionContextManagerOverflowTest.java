package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
	void tokenOverflowStillCompressesButNeverFlushes() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(120);
		props.setKeepLastMessages(3);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager mgr = new InMemorySessionContextManager(props, tokenCounter);

		String sid = "u1::s1";
		mgr.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		for (int i = 0; i < 20; i++) {
			mgr.addMessage(sid, new UserMessage("m" + i + ":" + repeat("x", 30)));
		}

		List<ChatMessage> ctx = mgr.getContext(sid);
		Assertions.assertFalse(ctx.isEmpty());
		Assertions.assertEquals("SYSTEM", ctx.get(0).getType().name());
		Assertions.assertTrue(ctx.size() <= 1 + props.getKeepLastMessages());
	}

	@Test
	void byteOverflowStillCompressesButNeverFlushes() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(100000);
		props.setMaxBytes(120);
		props.setKeepLastMessages(3);
		TokenCounter tokenCounter = text -> 1;
		InMemorySessionContextManager mgr = new InMemorySessionContextManager(props, tokenCounter);

		String sid = "u1::byte";
		mgr.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		for (int i = 0; i < 10; i++) {
			mgr.addMessage(sid, new UserMessage("m" + i + ":" + repeat("字", 20)));
		}

		List<ChatMessage> ctx = mgr.getContext(sid);
		Assertions.assertFalse(ctx.isEmpty());
		Assertions.assertEquals("SYSTEM", ctx.get(0).getType().name());
		Assertions.assertTrue(ctx.size() <= 1 + props.getKeepLastMessages());
	}

	@Test
	void overflowCompressionNoLongerDependsOnAppendOptions() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(120);
		props.setKeepLastMessages(3);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager mgr = new InMemorySessionContextManager(props, tokenCounter);

		String sid = "u1::no-flush";
		mgr.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		for (int i = 0; i < 20; i++) {
			mgr.addMessage(
				sid,
				new UserMessage("m" + i + ":" + repeat("x", 30)),
				SessionContextAppendOptions.withoutPreCompactionFlush()
			);
		}

		List<ChatMessage> ctx = mgr.getContext(sid);
		Assertions.assertFalse(ctx.isEmpty());
		Assertions.assertEquals("SYSTEM", ctx.get(0).getType().name());
		Assertions.assertTrue(ctx.size() <= 1 + props.getKeepLastMessages());
	}
}
