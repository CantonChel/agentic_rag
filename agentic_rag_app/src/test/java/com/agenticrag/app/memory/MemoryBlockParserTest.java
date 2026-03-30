package com.agenticrag.app.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryBlockParserTest {
	@TempDir
	Path tempDir;

	@Test
	void parsesStructuredMemoryBlocksWithStableLineRanges() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser parser = new MemoryBlockParser(fileService, new ObjectMapper());
		Path file = tempDir.resolve("memory/users/u1/daily/2026-03-29.md");
		Files.createDirectories(file.getParent());
		Files.writeString(
			file,
			"<!-- MEMORY_BLOCK {\"schema\":\"memory.v1\",\"kind\":\"durable\",\"block_id\":\"b1\",\"user_id\":\"u1\",\"session_id\":\"s1\",\"created_at\":\"2026-03-29T00:00:00Z\",\"trigger\":\"preflight_compact\",\"dedupe_key\":\"d1\"} -->\n"
				+ "- 第一条记忆\n"
				+ "- 第二条记忆\n"
				+ "<!-- /MEMORY_BLOCK -->\n",
			StandardCharsets.UTF_8
		);

		List<ParsedMemoryBlock> blocks = parser.parse("u1", file);
		Assertions.assertEquals(1, blocks.size());
		Assertions.assertEquals("b1", blocks.get(0).getMetadata().getBlockId());
		Assertions.assertEquals("daily_durable", blocks.get(0).getKind());
		Assertions.assertEquals(2, blocks.get(0).getStartLine());
		Assertions.assertEquals(3, blocks.get(0).getEndLine());
	}

	@Test
	void fallsBackToLegacyBlockForOldMarkdownFiles() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser parser = new MemoryBlockParser(fileService, new ObjectMapper());
		Path file = tempDir.resolve("memory/users/u1/sessions/2026-03-29-old.md");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "# Legacy\n- old content\n", StandardCharsets.UTF_8);

		List<ParsedMemoryBlock> blocks = parser.parse("u1", file);
		Assertions.assertEquals(1, blocks.size());
		Assertions.assertTrue(blocks.get(0).isLegacy());
		Assertions.assertEquals("session_archive", blocks.get(0).getKind());
		Assertions.assertTrue(blocks.get(0).getMetadata().getBlockId().startsWith("legacy-"));
	}

	private MemoryProperties props(Path workspaceRoot) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		return props;
	}
}
