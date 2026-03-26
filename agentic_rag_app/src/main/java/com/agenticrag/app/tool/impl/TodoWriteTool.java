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
	private static final String DESCRIPTION =
		"Use this tool to create and manage a structured task list for retrieval and research tasks. This helps you track progress, organize complex retrieval operations, and demonstrate thoroughness.\n"
			+ "\n"
			+ "CRITICAL - Focus on Retrieval Tasks Only:\n"
			+ "- This tool is for RETRIEVAL and RESEARCH tasks only.\n"
			+ "- DO NOT include summary, synthesis, or final answer steps.\n"
			+ "- Examples to include: \"Search KB for X\", \"Retrieve Y docs\", \"Collect A vs B specs\", \"Explore what exists in the KB about topic Z\"\n"
			+ "- Examples to EXCLUDE: \"Summarize findings\", \"Generate final answer\", \"Synthesize results\"\n"
			+ "- When a todo exists, execute retrieval steps in sequence and revise the plan only if thinking shows the current path is insufficient.\n"
			+ "\n"
			+ "## When to Use This Tool\n"
			+ "Use this tool proactively in these scenarios:\n"
			+ "1. Open-ended exploration or coverage discovery, such as understanding what is in the KB\n"
			+ "2. Complex multi-step tasks (often >=3 retrieval actions)\n"
			+ "3. Multi-topic, multi-source, comparison, or evaluation research\n"
			+ "4. User explicitly requests a plan or todo list\n"
			+ "5. User provides multiple parallel tasks\n"
			+ "\n"
			+ "## When NOT to Use This Tool\n"
			+ "Skip this tool when:\n"
			+ "1. The task is a single, straightforward action\n"
			+ "2. The task is trivial and does not require tracking\n"
			+ "3. The request is purely conversational or informational\n"
			+ "\n"
			+ "## Examples of When to Use the Todo List\n"
			+ "\n"
			+ "<example>\n"
			+ "User: Compare A and B based on documentation and provide differences.\n"
			+ "Assistant: I'll gather evidence for both A and B. Let me create a retrieval plan.\n"
			+ "*Creates todo list: 1) search_knowledge_keywords for A, 2) search_knowledge_keywords for B, 3) search_knowledge_base to expand authoritative specs, 4) collect comparison evidence*\n"
			+ "Assistant: I'll start by searching A in the knowledge base.\n"
			+ "\n"
			+ "<reasoning>\n"
			+ "The assistant used todo_write correctly because:\n"
			+ "1. The task requires multiple retrieval steps.\n"
			+ "2. Each step is a retrieval action.\n"
			+ "3. Synthesis is deferred to the thinking tool after retrieval.\n"
			+ "</reasoning>\n"
			+ "</example>\n"
			+ "\n"
			+ "<example>\n"
			+ "User: What is currently in the KB about company topics?\n"
			+ "Assistant: This is open-ended exploration, so I should create a retrieval plan before searching.\n"
			+ "*Creates todo list: 1) search_knowledge_keywords for company anchors, 2) search_knowledge_base for broader coverage, 3) inspect whether additional keyword variants are needed*\n"
			+ "<reasoning>\n"
			+ "The assistant used todo_write because the answer space is unknown and likely needs iterative retrieval.\n"
			+ "</reasoning>\n"
			+ "</example>\n"
			+ "\n"
			+ "## Examples of When NOT to Use the Todo List\n"
			+ "\n"
			+ "<example>\n"
			+ "User: What does git status do?\n"
			+ "Assistant: It shows the current state of your working directory and staging area.\n"
			+ "<reasoning>\n"
			+ "This is a simple informational request with no need for a retrieval plan.\n"
			+ "</reasoning>\n"
			+ "</example>\n";

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
