package com.agenticrag.app.tool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class KeywordLikeSearchToolRegistrationTest {
	@Autowired
	private ToolRouter toolRouter;

	@Test
	void registersKeywordLikeSearchTool() {
		Assertions.assertTrue(toolRouter.getTool("search_knowledge_keywords").isPresent());
	}
}
