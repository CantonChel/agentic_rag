package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.session.SessionManager;
import com.agenticrag.app.session.SessionSummaryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionControllerTest {
	@Test
	void replayMergesUserMessagesAndAssistantEventsInStableTimelineOrder() {
		SessionManager sessionManager = Mockito.mock(SessionManager.class);
		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		SessionReplayStore sessionReplayStore = Mockito.mock(SessionReplayStore.class);
		SessionSummaryService sessionSummaryService = Mockito.mock(SessionSummaryService.class);
		SessionController controller = new SessionController(
			sessionManager,
			contextManager,
			persistentMessageStore,
			sessionReplayStore,
			sessionSummaryService
		);

		StoredMessageEntity user = new StoredMessageEntity();
		user.setSessionId("u1::s1");
		user.setType("USER");
		user.setContent("请看看知识库里有什么");
		user.setCreatedAt(Instant.parse("2026-03-26T09:00:00Z"));

		SessionReplayStore.ReplayEventRecord turnStart = new SessionReplayStore.ReplayEventRecord(
			"u1::s1",
			LlmStreamEvent.turnStart("turn-1", 1L, Instant.parse("2026-03-26T09:00:01Z").toEpochMilli(), "u1::s1"),
			Instant.parse("2026-03-26T09:00:01Z")
		);
		SessionReplayStore.ReplayEventRecord thinking = new SessionReplayStore.ReplayEventRecord(
			"u1::s1",
			new LlmStreamEvent(
				"thinking",
				"这是开放式探索任务",
				null,
				null,
				null,
				"reasoning_details",
				"MiniMax-M2.7",
				1,
				null,
				"turn-1",
				2L,
				Instant.parse("2026-03-26T09:00:02Z").toEpochMilli(),
				null,
				null,
				null,
				null,
				null,
				null,
				null
			),
			Instant.parse("2026-03-26T09:00:02Z")
		);
		SessionReplayStore.ReplayEventRecord toolStart = new SessionReplayStore.ReplayEventRecord(
			"u1::s1",
			LlmStreamEvent.toolStart(
				"turn-1",
				3L,
				Instant.parse("2026-03-26T09:00:03Z").toEpochMilli(),
				1,
				"call-1",
				"search_knowledge_keywords",
				null
			),
			Instant.parse("2026-03-26T09:00:03Z")
		);

		Mockito.when(persistentMessageStore.list("u1::s1")).thenReturn(List.of(user));
		Mockito.when(sessionReplayStore.list("u1::s1")).thenReturn(List.of(toolStart, thinking, turnStart));

		List<SessionController.ReplayEntryView> replay = controller.replay("s1", "u1");

		Assertions.assertEquals(4, replay.size());
		Assertions.assertEquals("user_message", replay.get(0).getKind());
		Assertions.assertEquals("assistant_event", replay.get(1).getKind());
		Assertions.assertEquals("turn_start", replay.get(1).getType());
		Assertions.assertEquals("thinking", replay.get(2).getType());
		Assertions.assertEquals("reasoning_details", replay.get(2).getSource());
		Assertions.assertEquals("tool_start", replay.get(3).getType());
		Assertions.assertEquals("call-1", replay.get(3).getToolCallId());
	}

	@Test
	void replayReturnsEmptyWhenReplayEventsAreMissing() {
		SessionManager sessionManager = Mockito.mock(SessionManager.class);
		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		SessionReplayStore sessionReplayStore = Mockito.mock(SessionReplayStore.class);
		SessionSummaryService sessionSummaryService = Mockito.mock(SessionSummaryService.class);
		SessionController controller = new SessionController(
			sessionManager,
			contextManager,
			persistentMessageStore,
			sessionReplayStore,
			sessionSummaryService
		);

		Mockito.when(sessionReplayStore.list("u1::s1")).thenReturn(List.of());

		List<SessionController.ReplayEntryView> replay = controller.replay("s1", "u1");

		Assertions.assertTrue(replay.isEmpty());
		Mockito.verify(persistentMessageStore, Mockito.never()).list(Mockito.anyString());
	}
}
