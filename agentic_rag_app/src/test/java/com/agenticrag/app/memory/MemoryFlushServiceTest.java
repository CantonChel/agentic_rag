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

		MemoryFlushService service = new MemoryFlushService(props);
		List<ChatMessage> messages = Arrays.asList(
			new SystemMessage("system"),
			new UserMessage("用户提到要在周五前完成"),
			new AssistantMessage("收到，我会记录这个截止日期")
		);

		service.flushPreCompaction("u1::s1", messages);

		Path daily = tempDir.resolve("memory/users/u1/" + LocalDate.now() + ".md");
		Assertions.assertTrue(Files.exists(daily));
		String content = Files.readString(daily, StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("pre-compaction session=s1"));
		Assertions.assertTrue(content.contains("USER: 用户提到要在周五前完成"));
		Assertions.assertTrue(content.contains("ASSISTANT: 收到，我会记录这个截止日期"));
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

	private StoredMessageEntity message(String type, String content) {
		StoredMessageEntity entity = new StoredMessageEntity();
		entity.setType(type);
		entity.setContent(content);
		return entity;
	}
}
