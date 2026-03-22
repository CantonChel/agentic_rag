package com.agenticrag.app.tool.impl;

import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class TodoWriteTool implements Tool {
	private final ObjectMapper objectMapper;

	public TodoWriteTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "todo_write";
	}

	@Override
	public String description() {
		return "Create and manage a structured task list for retrieval and research tasks.";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");

		ObjectNode task = properties.putObject("task");
		task.put("type", "string");
		task.put("description", "The complex task or question you need to create a plan for.");

		ObjectNode steps = properties.putObject("steps");
		steps.put("type", "array");
		ObjectNode stepItem = steps.putObject("items");
		stepItem.put("type", "object");
		ObjectNode stepProps = stepItem.putObject("properties");
		stepProps.putObject("id").put("type", "string");
		stepProps.putObject("description").put("type", "string");
		stepProps.putObject("status").put("type", "string");
		ArrayNode stepRequired = stepItem.putArray("required");
		stepRequired.add("id");
		stepRequired.add("description");
		stepRequired.add("status");

		root.putArray("required").add("task").add("steps");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		String task = arguments != null && arguments.hasNonNull("task")
			? arguments.get("task").asText()
			: "";

		List<String> lines = new ArrayList<>();
		lines.add("任务: " + (task == null || task.trim().isEmpty() ? "(未提供)" : task.trim()));
		lines.add("步骤:");

		if (arguments != null && arguments.hasNonNull("steps") && arguments.get("steps").isArray()) {
			int idx = 1;
			for (JsonNode step : arguments.get("steps")) {
				String id = step.hasNonNull("id") ? step.get("id").asText() : "step" + idx;
				String desc = step.hasNonNull("description") ? step.get("description").asText() : "";
				String status = step.hasNonNull("status") ? step.get("status").asText() : "pending";
				lines.add(String.format("%d. [%s] %s (%s)", idx, id, desc, status));
				idx++;
			}
		} else {
			lines.add("(无步骤)");
		}

		return Mono.just(ToolResult.ok(String.join("\n", lines)));
	}
}
