package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class MemoryFlushServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void flushPreCompactionShouldAppendToUserDailyMemory() throws Exception {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		props.setUserMemoryBaseDir("memory/users");
		props.setFlushEnabled(true);
		props.setPreCompactionFlushEnabled(true);
		props.setFlushRecentMessages(10);

		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		Mockito.when(extractor.extractDurableMarkdown(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("pre-compaction"),
			Mockito.anyList()
		)).thenReturn("- **截止日期**：周五前完成");

		MemoryFlushService service = new MemoryFlushService(props, extractor);
		List<ChatMessage> messages = Arrays.asList(
			new SystemMessage("system"),
			new UserMessage("用户提到要在周五前完成"),
			new AssistantMessage("收到，我会记录这个截止日期")
		);

		service.flushPreCompaction("u1::s1", messages);

		Path daily = tempDir.resolve("memory/users/u1/" + LocalDate.now() + ".md");
		Assertions.assertTrue(Files.exists(daily));
		String content = Files.readString(daily, StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("Memory Flush"));
		Assertions.assertTrue(content.contains("Session: s1"));
		Assertions.assertTrue(content.contains("截止日期"));
	}

	@Test
	void flushOnSessionResetShouldWriteSessionSummaryFile() throws Exception {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		props.setUserMemoryBaseDir("memory/users");
		props.setFlushEnabled(true);
		props.setSessionResetFlushEnabled(true);
		props.setFlushRecentMessages(10);

		MemoryFlushService service = new MemoryFlushService(props);
		service.flushOnSessionReset(
			"u1::s88",
			null,
			Arrays.asList(message("USER", "我们约定周一提交"), message("ASSISTANT", "好的，我已记录"))
		);

		Path summary = tempDir.resolve("memory/users/u1/sessions/" + LocalDate.now() + "-s88.md");
		Assertions.assertTrue(Files.exists(summary));
		String content = Files.readString(summary, StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("session_id: s88"));
		Assertions.assertTrue(content.contains("USER: 我们约定周一提交"));
		Assertions.assertTrue(content.contains("ASSISTANT: 好的，我已记录"));
	}

	@Test
	void flushOnSessionSwitchCommandShouldWriteSlugSnapshotFile() throws Exception {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		props.setUserMemoryBaseDir("memory/users");
		props.setFlushEnabled(true);
		props.setSessionResetFlushEnabled(true);
		props.setSessionSnapshotRecentMessages(10);

		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		Mockito.when(extractor.generateSessionSlug(
			Mockito.eq("u1"),
			Mockito.eq("s99"),
			Mockito.anyList()
		)).thenReturn("product-decision");

		MemoryFlushService service = new MemoryFlushService(props, extractor);
		service.flushOnSessionSwitchCommand(
			"u1::s99",
			"command:/new",
			null,
			Arrays.asList(message("USER", "我们决定这周发布"), message("ASSISTANT", "已记录"))
		);

		Path snapshot = tempDir.resolve("memory/users/u1/sessions/" + LocalDate.now() + "-product-decision.md");
		Assertions.assertTrue(Files.exists(snapshot));
		String content = Files.readString(snapshot, StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("reason: command:/new"));
		Assertions.assertTrue(content.contains("USER: 我们决定这周发布"));
	}

	private StoredMessageEntity message(String type, String content) {
		StoredMessageEntity entity = new StoredMessageEntity();
		entity.setType(type);
		entity.setContent(content);
		return entity;
	}
}
