package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.SessionContextSnapshotStore;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.memory.MemoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class MemoryScopeControllerTest {
	@TempDir
	Path tempDir;

	@Test
	void scopesShouldReturnFourGroups() throws Exception {
		Files.writeString(tempDir.resolve("MEMORY.md"), "global", StandardCharsets.UTF_8);
		Files.createDirectories(tempDir.resolve("memory/users/u1/facts"));
		Files.createDirectories(tempDir.resolve("memory/users/u1/summaries"));
		Files.writeString(tempDir.resolve("memory/users/u1/facts/project.policy.md"), "fact", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("memory/users/u1/summaries/2026-04-07-reset.md"), "summary", StandardCharsets.UTF_8);

		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);
		Mockito.when(snapshotStore.listUserSnapshots("u1"))
			.thenReturn(List.of(new SessionContextSnapshotStore.SessionContextSnapshotSummary(
				"u1",
				"s1",
				"u1::s1",
				3L,
				Instant.parse("2026-04-07T00:00:00Z")
			)));

		MemoryScopeController controller = new MemoryScopeController(new MemoryFileService(props(tempDir)), snapshotStore);
		MemoryScopeController.MemoryScopeView view = controller.scopes("u1", true).block();

		Assertions.assertNotNull(view);
		Assertions.assertEquals(1, view.getGlobalFiles().size());
		Assertions.assertEquals(1, view.getFactFiles().size());
		Assertions.assertEquals(1, view.getSummaryFiles().size());
		Assertions.assertEquals(1, view.getSessionContexts().size());
		Assertions.assertEquals("s1", view.getSessionContexts().get(0).getSessionId());
	}

	@Test
	void sessionContextDetailShouldUseUserScopedSnapshotOnly() {
		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);
		Mockito.when(snapshotStore.listUserSnapshots("u1")).thenReturn(List.of());

		MemoryScopeController controller = new MemoryScopeController(new MemoryFileService(props(tempDir)), snapshotStore);

		ResponseStatusException error = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> controller.sessionContext("u1", "s1").block()
		);
		Assertions.assertEquals(404, error.getStatus().value());
	}

	@Test
	void sessionContextDetailShouldReturnOrderedMessages() {
		SessionContextSnapshotStore snapshotStore = Mockito.mock(SessionContextSnapshotStore.class);
		SessionContextSnapshotStore.SessionContextSnapshotSummary summary = new SessionContextSnapshotStore.SessionContextSnapshotSummary(
			"u1",
			"s1",
			"u1::s1",
			3L,
			Instant.parse("2026-04-07T01:02:03Z")
		);
		Mockito.when(snapshotStore.listUserSnapshots("u1")).thenReturn(List.of(summary));
		Mockito.when(snapshotStore.loadSnapshot("u1::s1")).thenReturn(
			List.of(new SystemMessage("SYSTEM"), new UserMessage("hello"), new AssistantMessage("world"))
		);

		MemoryScopeController controller = new MemoryScopeController(new MemoryFileService(props(tempDir)), snapshotStore);
		MemoryScopeController.SessionContextDetailView detail = controller.sessionContext("u1", "s1").block();

		Assertions.assertNotNull(detail);
		Assertions.assertEquals("s1", detail.getSessionId());
		Assertions.assertEquals(3, detail.getMessageCount());
		Assertions.assertEquals(3, detail.getMessages().size());
		Assertions.assertEquals("SYSTEM", detail.getMessages().get(0).getType());
		Assertions.assertEquals("hello", detail.getMessages().get(1).getContent());
		Assertions.assertEquals("world", detail.getMessages().get(2).getContent());
	}

	private MemoryProperties props(Path workspaceRoot) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		return props;
	}
}
