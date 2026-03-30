package com.agenticrag.app.memory;

import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class SessionArchiveServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void archiveWritesSessionArchiveBlockWithoutThinkingOrToolMessagesAndDedupes() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		Mockito.when(extractor.generateSessionSlug(
			Mockito.eq("u1"),
			Mockito.eq("s88"),
			Mockito.anyList()
		)).thenReturn("product-decision");

		SessionArchiveService service = new SessionArchiveService(props, extractor, fileService, blockParser);
		service.archive(
			"u1::s88",
			"command:/new",
			null,
			Arrays.asList(
				message("USER", "我们决定本周发布"),
				message("ASSISTANT", "收到，我会推进"),
				message("THINKING", "不应进入 archive"),
				message("TOOL_RESULT", "也不应进入 archive")
			)
		);
		service.archive(
			"u1::s88",
			"command:/new",
			null,
			Arrays.asList(
				message("USER", "我们决定本周发布"),
				message("ASSISTANT", "收到，我会推进")
			)
		);

		Path snapshot = tempDir.resolve("memory/users/u1/sessions/" + LocalDate.now() + "-product-decision.md");
		Assertions.assertTrue(Files.exists(snapshot));
		String content = Files.readString(snapshot, StandardCharsets.UTF_8);
		Assertions.assertEquals(1, countOccurrences(content, "<!-- MEMORY_BLOCK "));
		Assertions.assertTrue(content.contains("\"kind\":\"session_archive\""));
		Assertions.assertTrue(content.contains("\"reason\":\"command:/new\""));
		Assertions.assertTrue(content.contains("USER: 我们决定本周发布"));
		Assertions.assertTrue(content.contains("ASSISTANT: 收到，我会推进"));
		Assertions.assertFalse(content.contains("THINKING"));
		Assertions.assertFalse(content.contains("TOOL_RESULT"));
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

	private int countOccurrences(String text, String needle) {
		int count = 0;
		int from = 0;
		while (true) {
			int idx = text.indexOf(needle, from);
			if (idx < 0) {
				return count;
			}
			count++;
			from = idx + needle.length();
		}
	}
}
