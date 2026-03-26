package com.agenticrag.app.prompt;

import com.agenticrag.app.llm.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultSystemPromptManagerTest {
	@Test
	void agentModeUsesAgentBasePrompt() {
		SystemPromptProperties properties = new SystemPromptProperties();
		properties.setBase("base prompt");
		properties.setAgentBase("agent prompt");

		DefaultSystemPromptManager manager = new DefaultSystemPromptManager(properties, new ArrayList<>());

		String result = manager.build(new SystemPromptContext(LlmProvider.OPENAI, true, SystemPromptMode.AGENT));

		Assertions.assertEquals("agent prompt", result);
	}

	@Test
	void agentModeFallsBackToBaseWhenAgentPromptMissing() {
		SystemPromptProperties properties = new SystemPromptProperties();
		properties.setBase("base prompt");
		properties.setAgentBase("  ");

		DefaultSystemPromptManager manager = new DefaultSystemPromptManager(properties, new ArrayList<>());

		String result = manager.build(new SystemPromptContext(LlmProvider.MINIMAX, true, SystemPromptMode.AGENT));

		Assertions.assertEquals("base prompt", result);
	}
}
