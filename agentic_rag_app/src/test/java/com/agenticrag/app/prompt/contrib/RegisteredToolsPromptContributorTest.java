package com.agenticrag.app.prompt.contrib;

import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RegisteredToolsPromptContributorTest {
	@Test
	void onlyRendersAllowedTools() {
		ToolRouter toolRouter = new ToolRouter();
		ObjectMapper objectMapper = new ObjectMapper();
		toolRouter.register(new StubTool("calculator", objectMapper));
		toolRouter.register(new StubTool("memory_search", objectMapper));

		RegisteredToolsPromptContributor contributor = new RegisteredToolsPromptContributor(toolRouter, objectMapper);
		String output = contributor.contribute(
			new SystemPromptContext(LlmProvider.OPENAI, true, SystemPromptMode.AGENT, false, Set.of("calculator"))
		);

		Assertions.assertTrue(output.contains("calculator"));
		Assertions.assertFalse(output.contains("memory_search"));
	}

	private static final class StubTool implements Tool {
		private final String name;
		private final ObjectMapper objectMapper;

		private StubTool(String name, ObjectMapper objectMapper) {
			this.name = name;
			this.objectMapper = objectMapper;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			return name + " description";
		}

		@Override
		public JsonNode parametersSchema() {
			return objectMapper.createObjectNode().put("type", "object");
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, com.agenticrag.app.tool.ToolExecutionContext context) {
			return Mono.just(ToolResult.ok(name));
		}
	}
}
