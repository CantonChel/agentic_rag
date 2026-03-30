package com.agenticrag.app.tool;

import com.agenticrag.app.memory.MemoryReadResult;
import com.agenticrag.app.memory.MemoryRecallService;
import com.agenticrag.app.tool.impl.MemoryGetTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MemoryGetToolTest {
	@Test
	void readsExactMemoryLinesFromReturnedPathAndRange() {
		ObjectMapper objectMapper = new ObjectMapper();
		MemoryRecallService memoryRecallService = Mockito.mock(MemoryRecallService.class);
		Mockito.when(memoryRecallService.get("u1", "memory/users/u1/daily/2026-03-29.md", 12, 13))
			.thenReturn(new MemoryReadResult(
				"memory/users/u1/daily/2026-03-29.md",
				"daily_durable",
				"b1",
				12,
				13,
				"- 截止日期：周五\n- 偏好：中文"
			));

		MemoryGetTool tool = new MemoryGetTool(objectMapper, memoryRecallService);
		ObjectNode args = objectMapper.createObjectNode();
		args.put("path", "memory/users/u1/daily/2026-03-29.md");
		args.put("lineStart", 12);
		args.put("lineEnd", 13);

		ToolResult result = tool.execute(args, new ToolExecutionContext("req", "u1", "s1")).block();

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isSuccess());
		Assertions.assertTrue(result.getOutput().contains("path: memory/users/u1/daily/2026-03-29.md"));
		Assertions.assertTrue(result.getOutput().contains("12 | - 截止日期：周五"));
		Assertions.assertTrue(result.getOutput().contains("13 | - 偏好：中文"));
	}

	@Test
	void rejectsUnauthorizedOrMissingRanges() {
		ObjectMapper objectMapper = new ObjectMapper();
		MemoryRecallService memoryRecallService = Mockito.mock(MemoryRecallService.class);
		Mockito.when(memoryRecallService.get("u1", "memory/users/u2/secret.md", 1, 1)).thenReturn(null);

		MemoryGetTool tool = new MemoryGetTool(objectMapper, memoryRecallService);
		ObjectNode args = objectMapper.createObjectNode();
		args.put("path", "memory/users/u2/secret.md");
		args.put("lineStart", 1);
		args.put("lineEnd", 1);

		ToolResult result = tool.execute(args, new ToolExecutionContext("req", "u1", "s1")).block();

		Assertions.assertNotNull(result);
		Assertions.assertFalse(result.isSuccess());
		Assertions.assertTrue(result.getError().contains("not found"));
	}
}
