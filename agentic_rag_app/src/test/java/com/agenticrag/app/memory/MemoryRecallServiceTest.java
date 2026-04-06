package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryIndexSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class MemoryRecallServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void searchDelegatesToPgSearchService() {
		MemoryProperties properties = properties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryBlockParser parser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryIndexSearchService searchService = Mockito.mock(MemoryIndexSearchService.class);
		MemoryRecallService recallService = new MemoryRecallService(properties, fileService, parser, searchService);
		List<MemorySearchHit> expected = List.of(
			new MemorySearchHit("memory/users/u1/facts/project.reminder.md", "fact", "b1", 3, 4, 0.92, "只对接企业微信")
		);
		Mockito.when(searchService.search("u1", "企业微信", 3)).thenReturn(expected);

		List<MemorySearchHit> result = recallService.search("u1", "企业微信", 3);

		Assertions.assertSame(expected, result);
		Mockito.verify(searchService).search("u1", "企业微信", 3);
	}

	@Test
	void getReadsExactMarkdownLinesWithoutCallingSearchService() throws Exception {
		MemoryProperties properties = properties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryBlockParser parser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryIndexSearchService searchService = Mockito.mock(MemoryIndexSearchService.class);
		MemoryRecallService recallService = new MemoryRecallService(properties, fileService, parser, searchService);
		Path file = tempDir.resolve("memory/users/u1/facts/project.reminder.md");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "第一行\n只对接企业微信\n先不开审批\n", StandardCharsets.UTF_8);

		MemoryReadResult result = recallService.get("u1", "memory/users/u1/facts/project.reminder.md", 2, 3);

		Assertions.assertNotNull(result);
		Assertions.assertEquals("memory/users/u1/facts/project.reminder.md", result.getPath());
		Assertions.assertEquals("fact", result.getKind());
		Assertions.assertTrue(result.getBlockId() != null && result.getBlockId().startsWith("legacy-"));
		Assertions.assertEquals("只对接企业微信\n先不开审批", result.getContent());
		Mockito.verifyNoInteractions(searchService);
	}

	private MemoryProperties properties() {
		MemoryProperties properties = new MemoryProperties();
		properties.setWorkspaceRoot(tempDir.toString());
		properties.setUserMemoryBaseDir("memory/users");
		return properties;
	}
}
