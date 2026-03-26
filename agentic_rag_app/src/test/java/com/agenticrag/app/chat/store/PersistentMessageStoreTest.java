package com.agenticrag.app.chat.store;

import com.agenticrag.app.chat.message.ChatMessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PersistentMessageStoreTest {
	@Test
	void ensureSystemPromptUpdatesExistingStoredSystemPromptWhenChanged() {
		StoredMessageRepository repo = Mockito.mock(StoredMessageRepository.class);
		ObjectMapper objectMapper = new ObjectMapper();
		PersistentMessageStore store = new PersistentMessageStore(repo, objectMapper);

		StoredMessageEntity entity = new StoredMessageEntity();
		entity.setSessionId("s1");
		entity.setType(ChatMessageType.SYSTEM.name());
		entity.setContent("base prompt");

		Mockito.when(repo.existsBySessionIdAndType("s1", ChatMessageType.SYSTEM.name())).thenReturn(true);
		Mockito.when(repo.findBySessionIdOrderByIdAsc("s1")).thenReturn(List.of(entity));

		store.ensureSystemPrompt("s1", "agent prompt");

		Assertions.assertEquals("agent prompt", entity.getContent());
		Mockito.verify(repo).save(entity);
	}

	@Test
	void ensureSystemPromptAppendsSystemMessageWhenMissing() {
		StoredMessageRepository repo = Mockito.mock(StoredMessageRepository.class);
		ObjectMapper objectMapper = new ObjectMapper();
		PersistentMessageStore store = new PersistentMessageStore(repo, objectMapper);

		Mockito.when(repo.existsBySessionIdAndType("s2", ChatMessageType.SYSTEM.name())).thenReturn(false);

		store.ensureSystemPrompt("s2", "prompt");

		ArgumentCaptor<StoredMessageEntity> captor = ArgumentCaptor.forClass(StoredMessageEntity.class);
		Mockito.verify(repo).save(captor.capture());
		StoredMessageEntity saved = captor.getValue();
		Assertions.assertEquals("s2", saved.getSessionId());
		Assertions.assertEquals(ChatMessageType.SYSTEM.name(), saved.getType());
		Assertions.assertEquals("prompt", saved.getContent());
	}
}
