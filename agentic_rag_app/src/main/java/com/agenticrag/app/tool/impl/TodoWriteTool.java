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
	// todo_write 工具英文提示词（正文英文，注释中文；含正反示例与约束）
	private static final String DESCRIPTION = """
Use this tool to create and manage a structured task list for retrieval and research tasks. This helps you track progress, organize complex retrieval operations, and demonstrate thoroughness.

CRITICAL - Focus on Retrieval Tasks Only:
- This tool is for RETRIEVAL and RESEARCH tasks only.
- DO NOT include summary, synthesis, or final answer steps.
- Examples to include: "Search KB for X", "Retrieve Y docs", "Collect A vs B specs"
- Examples to EXCLUDE: "Summarize findings", "Generate final answer", "Synthesize results"

## When to Use This Tool
Use this tool proactively in these scenarios:
1. Complex multi-step tasks (>=3 retrieval actions)
2. Multi-topic or multi-source research
3. User explicitly requests a plan or todo list
4. User provides multiple parallel tasks

## When NOT to Use This Tool
Skip this tool when:
1. The task is a single, straightforward action
2. The task is trivial and does not require tracking
3. The request is purely conversational or informational

## Examples of When to Use the Todo List

<example>
User: Compare A and B based on documentation and provide differences.
Assistant: I'll gather evidence for both A and B. Let me create a retrieval plan.
*Creates todo list: 1) Search KB for A docs, 2) Search KB for B docs, 3) Retrieve authoritative specs, 4) Collect comparison data*
Assistant: I'll start by searching A in the knowledge base.

<reasoning>
The assistant used todo_write correctly because:
1. The task requires multiple retrieval steps.
2. Each step is a retrieval action.
3. Synthesis is deferred to the thinking tool after retrieval.
</reasoning>
</example>

## Examples of When NOT to Use the Todo List

<example>
User: What does git status do?
Assistant: It shows the current state of your working directory and staging area.
<reasoning>
This is a simple informational request with no need for a retrieval plan.
</reasoning>
</example>
""";

	public TodoWriteTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "todo_write";
	}

	@Override
	public String description() {
		return DESCRIPTION;
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
