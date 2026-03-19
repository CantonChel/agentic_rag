package com.agenticrag.app.tool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class KnowledgeSearchToolRegistrationTest {
	@Autowired
	private ToolRouter toolRouter;

	@Test
	void registersKnowledgeSearchTool() {
		Assertions.assertTrue(toolRouter.getTool("search_knowledge_base").isPresent());
	}
}

