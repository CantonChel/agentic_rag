package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class SessionArchiveServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void archiveWritesSessionSummaryFromProjectedContextInsteadOfPersistedTranscript() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		Mockito.when(extractor.generateSessionSummary(
			Mockito.eq("u1"),
			Mockito.eq("s88"),
			Mockito.eq("command:/new"),
			Mockito.anyList()
		)).thenReturn("- 决定本周发布\n- 下一步补测试");
		Mockito.when(extractor.generateSessionSlug(
			Mockito.eq("u1"),
			Mockito.eq("s88"),
			Mockito.anyList()
		)).thenReturn("product-decision");

		SessionArchiveService service = new SessionArchiveService(props, extractor, fileService, blockParser);
		service.archive(
			"u1::s88",
			"command:/new",
			projectedMessages(18),
			List.of(message("USER", "这条持久化原文不应该直接进入 summary"))
		);

		Path summary = tempDir.resolve("memory/users/u1/summaries/" + LocalDate.now() + "-product-decision.md");
		Assertions.assertTrue(Files.exists(summary));
		String content = Files.readString(summary, StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("\"kind\":\"session_summary\""));
		Assertions.assertTrue(content.contains("\"reason\":\"command:/new\""));
		Assertions.assertTrue(content.contains("- 决定本周发布"));
		Assertions.assertTrue(content.contains("- 下一步补测试"));
		Assertions.assertFalse(content.contains("持久化原文"));

		Mockito.verify(extractor).generateSessionSummary(
			Mockito.eq("u1"),
			Mockito.eq("s88"),
			Mockito.eq("command:/new"),
			Mockito.argThat(lines -> lines != null && lines.size() == 15 && lines.get(0).startsWith("ASSISTANT: 第11轮"))
		);
	}

	private List<ChatMessage> projectedMessages(int rounds) {
		List<ChatMessage> messages = new ArrayList<>();
		messages.add(new SystemMessage("system"));
		for (int i = 1; i <= rounds; i++) {
			messages.add(new UserMessage("第" + i + "轮用户消息"));
			messages.add(new AssistantMessage("第" + i + "轮助手消息"));
		}
		return messages;
	}

	private StoredMessageEntity message(String type, String content) {
		StoredMessageEntity entity = new StoredMessageEntity();
		entity.setType(type);
		entity.setContent(content);
		return entity;
	}

	private MemoryProperties props(Path workspaceRoot) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		return props;
	}
}
