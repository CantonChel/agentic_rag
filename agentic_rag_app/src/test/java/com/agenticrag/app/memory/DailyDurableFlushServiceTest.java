package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class DailyDurableFlushServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void flushWritesDailyDurableBlockAndDedupesWithinTheSameDay() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		Mockito.when(extractor.extractDurableMarkdown(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn("- 截止日期：周五前完成");

		DailyDurableFlushService service = new DailyDurableFlushService(props, extractor, fileService, blockParser);
		List<ChatMessage> messages = Arrays.asList(
			new SystemMessage("system"),
			new UserMessage("用户提到周五前完成"),
			new AssistantMessage("我会记住截止日期")
		);

		service.flush("u1::s1", messages);
		service.flush("u1::s1", messages);

		Path daily = tempDir.resolve("memory/users/u1/daily/" + LocalDate.now() + ".md");
		Assertions.assertTrue(Files.exists(daily));
		String content = Files.readString(daily, StandardCharsets.UTF_8);
		Assertions.assertEquals(1, countOccurrences(content, "<!-- MEMORY_BLOCK "));
		Assertions.assertTrue(content.contains("\"kind\":\"durable\""));
		Assertions.assertTrue(content.contains("截止日期"));
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
