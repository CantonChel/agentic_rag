package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.memory.DailyDurableFlushService;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionContextPreflightCompactorTest {
	@Test
	void underPreflightThresholdKeepsContextUntouched() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(600);
		props.setPreflightReserveTokens(180);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(props, tokenCounter, snapshotStore);
		String sid = "u1::s1";

		contextManager.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		contextManager.addMessage(sid, new UserMessage(repeat("u", 80)));
		contextManager.addMessage(sid, new AssistantMessage(repeat("a", 80)));

		List<String> before = toSequence(contextManager.getContext(sid));
		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		SessionContextPreflightCompactor compactor = new SessionContextPreflightCompactor(
			contextManager,
			new SessionContextBudgetEvaluator(props, tokenCounter),
			dailyDurableFlushService
		);

		List<ChatMessage> prepared = compactor.prepareForTurn(sid, SessionContextAppendOptions.defaults());

		Assertions.assertEquals(before, toSequence(prepared));
		Assertions.assertEquals(before, toSequence(contextManager.getContext(sid)));
		Mockito.verify(dailyDurableFlushService, Mockito.never())
			.flush(Mockito.anyString(), Mockito.anyList());
	}

	@Test
	void tokenPreflightFlushesAndCompactsToLatestCompleteTurns() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(600);
		props.setPreflightReserveTokens(180);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(props, tokenCounter, snapshotStore);
		String sid = "u1::token";

		contextManager.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		contextManager.addMessage(sid, new UserMessage("u1-" + repeat("x", 108)));
		contextManager.addMessage(sid, new AssistantMessage("a1-" + repeat("x", 108)));
		contextManager.addMessage(sid, new UserMessage("u2-" + repeat("y", 108)));
		contextManager.addMessage(sid, new AssistantMessage("a2-" + repeat("y", 108)));

		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		SessionContextPreflightCompactor compactor = new SessionContextPreflightCompactor(
			contextManager,
			new SessionContextBudgetEvaluator(props, tokenCounter),
			dailyDurableFlushService
		);
		Mockito.reset(snapshotStore);

		List<ChatMessage> prepared = compactor.prepareForTurn(sid, SessionContextAppendOptions.defaults());

		Assertions.assertEquals(ChatMessageType.SYSTEM, prepared.get(0).getType());
		Assertions.assertEquals(3, prepared.size());
		Assertions.assertTrue(prepared.stream().anyMatch(message -> contentOf(message).startsWith("u2-")));
		Assertions.assertTrue(prepared.stream().anyMatch(message -> contentOf(message).startsWith("a2-")));
		Assertions.assertTrue(prepared.stream().noneMatch(message -> contentOf(message).startsWith("u1-")));
		Assertions.assertTrue(prepared.stream().noneMatch(message -> contentOf(message).startsWith("a1-")));
		Assertions.assertEquals(toSequence(prepared), toSequence(contextManager.getContext(sid)));
		Mockito.verify(dailyDurableFlushService, Mockito.times(1))
			.flush(Mockito.eq(sid), Mockito.anyList());
		Mockito.verify(snapshotStore, Mockito.times(1)).replaceSnapshot(Mockito.eq(sid), Mockito.anyList());
		Mockito.verify(snapshotStore, Mockito.never()).deleteSnapshot(Mockito.anyString());
	}

	@Test
	void bytePreflightFlushesAndCompactsWhenByteThresholdIsExceeded() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(100000);
		props.setMaxBytes(520);
		props.setPreflightReserveBytes(160);
		TokenCounter tokenCounter = text -> 1;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(
			props,
			tokenCounter,
			Mockito.mock(SessionContextSnapshotStore.class)
		);
		String sid = "u1::byte";

		contextManager.ensureSystemPrompt(sid, "SYSTEM");
		contextManager.addMessage(sid, new UserMessage("u1-" + repeat("字", 39)));
		contextManager.addMessage(sid, new AssistantMessage("a1-" + repeat("字", 39)));
		contextManager.addMessage(sid, new UserMessage("u2-" + repeat("文", 39)));
		contextManager.addMessage(sid, new AssistantMessage("a2-" + repeat("文", 39)));

		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		SessionContextPreflightCompactor compactor = new SessionContextPreflightCompactor(
			contextManager,
			new SessionContextBudgetEvaluator(props, tokenCounter),
			dailyDurableFlushService
		);

		List<ChatMessage> prepared = compactor.prepareForTurn(sid, SessionContextAppendOptions.defaults());

		Assertions.assertEquals(3, prepared.size());
		Assertions.assertTrue(prepared.stream().anyMatch(message -> contentOf(message).startsWith("u2-")));
		Assertions.assertTrue(prepared.stream().noneMatch(message -> contentOf(message).startsWith("u1-")));
		Mockito.verify(dailyDurableFlushService, Mockito.times(1))
			.flush(Mockito.eq(sid), Mockito.anyList());
	}

	@Test
	void withoutPreCompactionFlushStillCompactsSessionHistory() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(600);
		props.setPreflightReserveTokens(180);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(
			props,
			tokenCounter,
			Mockito.mock(SessionContextSnapshotStore.class)
		);
		String sid = "u1::no-flush";

		contextManager.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		contextManager.addMessage(sid, new UserMessage("u1-" + repeat("x", 108)));
		contextManager.addMessage(sid, new AssistantMessage("a1-" + repeat("x", 108)));
		contextManager.addMessage(sid, new UserMessage("u2-" + repeat("y", 108)));
		contextManager.addMessage(sid, new AssistantMessage("a2-" + repeat("y", 108)));

		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		SessionContextPreflightCompactor compactor = new SessionContextPreflightCompactor(
			contextManager,
			new SessionContextBudgetEvaluator(props, tokenCounter),
			dailyDurableFlushService
		);

		List<ChatMessage> prepared = compactor.prepareForTurn(
			sid,
			SessionContextAppendOptions.withoutPreCompactionFlush()
		);

		Assertions.assertEquals(3, prepared.size());
		Assertions.assertTrue(prepared.stream().anyMatch(message -> contentOf(message).startsWith("u2-")));
		Assertions.assertTrue(prepared.stream().noneMatch(message -> contentOf(message).startsWith("u1-")));
		Mockito.verify(dailyDurableFlushService, Mockito.never())
			.flush(Mockito.anyString(), Mockito.anyList());
	}

	@Test
	void latestCompleteTurnIsPreservedEvenWhenItStillExceedsTarget() {
		SessionContextProperties props = new SessionContextProperties();
		props.setMaxTokens(600);
		props.setPreflightReserveTokens(180);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(
			props,
			tokenCounter,
			Mockito.mock(SessionContextSnapshotStore.class)
		);
		String sid = "u1::latest-turn";

		contextManager.ensureSystemPrompt(sid, "SYSTEM_PROMPT");
		contextManager.addMessage(sid, new UserMessage("u1-" + repeat("x", 48)));
		contextManager.addMessage(sid, new AssistantMessage("a1-" + repeat("x", 48)));
		contextManager.addMessage(sid, new UserMessage("u2-" + repeat("y", 218)));
		contextManager.addMessage(sid, new AssistantMessage("a2-" + repeat("y", 218)));

		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		SessionContextBudgetEvaluator budgetEvaluator = new SessionContextBudgetEvaluator(props, tokenCounter);
		SessionContextPreflightCompactor compactor = new SessionContextPreflightCompactor(
			contextManager,
			budgetEvaluator,
			dailyDurableFlushService
		);

		List<ChatMessage> prepared = compactor.prepareForTurn(sid, SessionContextAppendOptions.defaults());

		Assertions.assertEquals(3, prepared.size());
		Assertions.assertTrue(prepared.stream().anyMatch(message -> contentOf(message).startsWith("u2-")));
		Assertions.assertTrue(prepared.stream().anyMatch(message -> contentOf(message).startsWith("a2-")));
		Assertions.assertTrue(prepared.stream().noneMatch(message -> contentOf(message).startsWith("u1-")));
		Assertions.assertTrue(budgetEvaluator.evaluatePreflight(prepared).isExceeded());
		Mockito.verify(dailyDurableFlushService, Mockito.times(1))
			.flush(Mockito.eq(sid), Mockito.anyList());
	}

	private static String contentOf(ChatMessage message) {
		return message != null && message.getContent() != null ? message.getContent() : "";
	}

	private static List<String> toSequence(List<ChatMessage> messages) {
		List<String> sequence = new ArrayList<>();
		for (ChatMessage message : messages) {
			if (message == null) {
				sequence.add("null");
				continue;
			}
			sequence.add(message.getType() + ":" + contentOf(message));
		}
		return sequence;
	}

	private static String repeat(String value, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; i++) {
			sb.append(value);
		}
		return sb.toString();
	}
}
