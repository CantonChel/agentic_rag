package com.agenticrag.app.prompt.contrib;

import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptMode;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MemoryPromptContributorTest {
	@Test
	void skipsMemoryPolicyWhenMemoryIsDisabled() {
		MemoryPromptContributor contributor = new MemoryPromptContributor();

		String output = contributor.contribute(
			new SystemPromptContext(LlmProvider.OPENAI, true, SystemPromptMode.AGENT, false, Set.of("calculator"))
		);

		Assertions.assertEquals("", output);
	}

	@Test
	void rendersSearchThenGetPolicyWhenBothToolsAreVisible() {
		MemoryPromptContributor contributor = new MemoryPromptContributor();

		String output = contributor.contribute(
			new SystemPromptContext(
				LlmProvider.OPENAI,
				true,
				SystemPromptMode.AGENT,
				true,
				Set.of("memory_search", "memory_get")
			)
		);

		Assertions.assertTrue(output.contains("run memory_search"));
		Assertions.assertTrue(output.contains("use memory_get"));
	}
}
