package com.agenticrag.app.session;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.memory.MemoryFlushService;
import java.util.Collection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionManagerIsolationTest {
	@Test
	void listsSessionsByUserOnly() {
		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		SessionManager sessionManager = new SessionManager(contextManager, persistentMessageStore);

		ChatSession u1s1 = sessionManager.create("u1");
		sessionManager.create("u2");
		ChatSession u1s2 = sessionManager.create("u1");

		Collection<ChatSession> u1Sessions = sessionManager.list("u1");
		Collection<ChatSession> u2Sessions = sessionManager.list("u2");
		Assertions.assertEquals(2, u1Sessions.size());
		Assertions.assertEquals(1, u2Sessions.size());
		Assertions.assertTrue(u1Sessions.stream().anyMatch(s -> u1s1.getId().equals(s.getId())));
		Assertions.assertTrue(u1Sessions.stream().anyMatch(s -> u1s2.getId().equals(s.getId())));
	}

	@Test
	void deleteUsesScopedSessionId() {
		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		SessionManager sessionManager = new SessionManager(contextManager, persistentMessageStore);

		sessionManager.delete("u1", "s1");

		Mockito.verify(contextManager).clear("u1::s1");
		Mockito.verify(persistentMessageStore).clear("u1::s1");
	}

	@Test
	void deleteTriggersSessionResetFlush() {
		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		MemoryFlushService memoryFlushService = Mockito.mock(MemoryFlushService.class);
		SessionManager sessionManager = new SessionManager(contextManager, persistentMessageStore, memoryFlushService);

		sessionManager.delete("u1", "s1");

		Mockito.verify(memoryFlushService).flushOnSessionReset(
			Mockito.eq("u1::s1"),
			Mockito.any(),
			Mockito.any()
		);
	}

	@Test
	void deleteAlsoClearsReplayEvents() {
		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		SessionReplayStore sessionReplayStore = Mockito.mock(SessionReplayStore.class);
		SessionManager sessionManager = new SessionManager(contextManager, persistentMessageStore, sessionReplayStore, null);

		sessionManager.delete("u1", "s1");

		Mockito.verify(sessionReplayStore).clear("u1::s1");
	}
}
