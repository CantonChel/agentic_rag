package com.agenticrag.app.chat.store;

import com.agenticrag.app.llm.LlmStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SessionReplayStoreTest {
	@Test
	void appendPersistsReplayEventWithPreviewJson() {
		SessionReplayEventRepository repo = Mockito.mock(SessionReplayEventRepository.class);
		SessionReplayStore store = new SessionReplayStore(repo, new ObjectMapper());

		LlmStreamEvent event = new LlmStreamEvent(
			"tool_end",
			null,
			null,
			null,
			null,
			null,
			"MiniMax-M2.7",
			2,
			null,
			"turn-1",
			9L,
			123456789L,
			"call-1",
			"search_knowledge_keywords",
			"success",
			35L,
			JsonNodeFactory.instance.objectNode().put("query", "知识库"),
			JsonNodeFactory.instance.objectNode().put("text", "<context></context>"),
			null
		);

		store.append("u1::s1", event);

		ArgumentCaptor<SessionReplayEventEntity> captor = ArgumentCaptor.forClass(SessionReplayEventEntity.class);
		Mockito.verify(repo).save(captor.capture());
		SessionReplayEventEntity saved = captor.getValue();
		Assertions.assertEquals("u1::s1", saved.getSessionId());
		Assertions.assertEquals("tool_end", saved.getType());
		Assertions.assertEquals("turn-1", saved.getTurnId());
		Assertions.assertEquals(9L, saved.getSequenceId());
		Assertions.assertTrue(saved.getArgsPreviewJson().contains("知识库"));
		Assertions.assertTrue(saved.getResultPreviewJson().contains("<context>"));
	}

	@Test
	void appendSkipsSessionSwitchEvents() {
		SessionReplayEventRepository repo = Mockito.mock(SessionReplayEventRepository.class);
		SessionReplayStore store = new SessionReplayStore(repo, new ObjectMapper());

		store.append("u1::s1", LlmStreamEvent.sessionSwitched("s2"));
		store.append("u1::s1", LlmStreamEvent.done("session_switched", null, "turn-1", 3L, 100L, null));

		Mockito.verify(repo, Mockito.never()).save(Mockito.any(SessionReplayEventEntity.class));
	}

	@Test
	void listReconstructsStreamEventAndSortMetadata() {
		SessionReplayEventRepository repo = Mockito.mock(SessionReplayEventRepository.class);
		SessionReplayStore store = new SessionReplayStore(repo, new ObjectMapper());

		SessionReplayEventEntity entity = new SessionReplayEventEntity();
		entity.setSessionId("u1::s1");
		entity.setType("thinking");
		entity.setContent("这是回放思考");
		entity.setSource("reasoning_details");
		entity.setTurnId("turn-1");
		entity.setSequenceId(2L);
		entity.setRoundId(1);
		entity.setEventTs(123L);
		entity.setCreatedAt(Instant.parse("2026-03-26T12:00:00Z"));
		Mockito.when(repo.findBySessionIdOrderByEventTsAscSequenceIdAscIdAsc("u1::s1")).thenReturn(List.of(entity));

		List<SessionReplayStore.ReplayEventRecord> records = store.list("u1::s1");

		Assertions.assertEquals(1, records.size());
		Assertions.assertEquals("thinking", records.get(0).getEvent().getType());
		Assertions.assertEquals("这是回放思考", records.get(0).getEvent().getContent());
		Assertions.assertEquals("reasoning_details", records.get(0).getEvent().getSource());
		Assertions.assertEquals(123L, records.get(0).getEvent().getTs());
		Assertions.assertEquals(Instant.parse("2026-03-26T12:00:00Z"), records.get(0).getCreatedAt());
	}
}
