package com.agenticrag.app.session;

import com.agenticrag.app.chat.store.PersistentMessageStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionSummaryServiceTest {
	@Test
	void sortsSessionsByLatestActivityAndExposesMetadata() {
		SessionManager sessionManager = Mockito.mock(SessionManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		SessionSummaryService service = new SessionSummaryService(sessionManager, persistentMessageStore);

		Instant createdAt = Instant.parse("2026-03-26T09:00:00Z");
		ChatSession emptySession = new ChatSession("empty-session", createdAt);
		Mockito.when(sessionManager.list("u1")).thenReturn(List.of(emptySession));
		Mockito.when(persistentMessageStore.listSessionStats()).thenReturn(
			List.of(
				new PersistentMessageStore.SessionMessageStats(
					"u1::active-session",
					Instant.parse("2026-03-26T08:00:00Z"),
					Instant.parse("2026-03-26T12:00:00Z"),
					3
				),
				new PersistentMessageStore.SessionMessageStats(
					"u2::other-user-session",
					Instant.parse("2026-03-26T07:00:00Z"),
					Instant.parse("2026-03-26T13:00:00Z"),
					4
				)
			)
		);

		List<SessionSummaryService.SessionSummary> summaries = service.listForUser("u1");

		Assertions.assertEquals(2, summaries.size());
		Assertions.assertEquals("active-session", summaries.get(0).getSessionId());
		Assertions.assertEquals(Instant.parse("2026-03-26T12:00:00Z"), summaries.get(0).getLastActiveAt());
		Assertions.assertTrue(summaries.get(0).isHasMessages());
		Assertions.assertEquals(Instant.parse("2026-03-26T08:00:00Z"), summaries.get(0).getCreatedAt());

		Assertions.assertEquals("empty-session", summaries.get(1).getSessionId());
		Assertions.assertEquals(createdAt, summaries.get(1).getCreatedAt());
		Assertions.assertEquals(createdAt, summaries.get(1).getLastActiveAt());
		Assertions.assertFalse(summaries.get(1).isHasMessages());
	}
}
