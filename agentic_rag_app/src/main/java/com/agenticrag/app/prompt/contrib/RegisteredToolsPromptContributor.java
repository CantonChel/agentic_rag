package com.agenticrag.app.prompt.contrib;

import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptContributor;
import com.agenticrag.app.tool.ToolDefinition;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class RegisteredToolsPromptContributor implements SystemPromptContributor {
	private final ToolRouter toolRouter;
	private final ObjectMapper objectMapper;

	public RegisteredToolsPromptContributor(ToolRouter toolRouter, ObjectMapper objectMapper) {
		this.toolRouter = toolRouter;
		this.objectMapper = objectMapper;
	}

	@Override
	public String id() {
		return "registered_tools";
	}

	@Override
	public String contribute(SystemPromptContext context) {
		if (!context.isIncludeTools()) {
			return "";
		}

		Collection<ToolDefinition> tools = toolRouter.getToolDefinitions();
		if (tools.isEmpty()) {
			return "";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Available tools:\n");
		for (ToolDefinition tool : tools) {
			sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
			sb.append("  parameters: ").append(toJson(tool.getParametersSchema())).append("\n");
		}
		return sb.toString();
	}

	private String toJson(Object o) {
		try {
			return objectMapper.writeValueAsString(o);
		} catch (JsonProcessingException e) {
			return String.valueOf(o);
		}
	}
}

