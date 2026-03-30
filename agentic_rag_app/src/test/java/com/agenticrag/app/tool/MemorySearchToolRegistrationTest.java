package com.agenticrag.app.tool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MemorySearchToolRegistrationTest {
	@Autowired
	private ToolRouter toolRouter;

	@Test
	void registersMemoryRecallTools() {
		Assertions.assertTrue(toolRouter.getTool("memory_search").isPresent());
		Assertions.assertTrue(toolRouter.getTool("memory_get").isPresent());
	}
}
