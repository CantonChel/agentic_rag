package com.agenticrag.app.tool.impl;

import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ThinkingTool implements Tool {
	private final ObjectMapper objectMapper;
	// 思考工具英文提示词（正文英文，注释中文；含正反示例与约束）
	private static final String DESCRIPTION =
		"Use this tool to perform reasoning, synthesis, evaluation, or correction. This tool is for THINKING only.\n"
			+ "\n"
			+ "CRITICAL - Focus on Reasoning/Synthesis Only:\n"
			+ "- Use this tool to combine evidence, resolve tradeoffs, or correct mistakes.\n"
			+ "- Use this tool after key retrieval steps to judge evidence sufficiency, detect gaps, and decide the next retrieval move.\n"
			+ "- DO NOT use this tool for retrieval itself.\n"
			+ "- The output should be the reasoning content to show in thinking events.\n"
			+ "\n"
			+ "## When to Use This Tool\n"
			+ "Use this tool proactively in these scenarios:\n"
			+ "1. You need to compare or evaluate options\n"
			+ "2. You must synthesize multiple evidence sources\n"
			+ "3. You need multi-step reasoning or correction\n"
			+ "4. A retrieval result may be incomplete, weak, irrelevant, or contradictory\n"
			+ "5. You are about to provide a non-trivial final response\n"
			+ "\n"
			+ "## When NOT to Use This Tool\n"
			+ "Skip this tool when:\n"
			+ "1. The question is trivial and needs no reasoning\n"
			+ "2. You only need to call a retrieval tool\n"
			+ "3. You are just echoing a single source\n"
			+ "\n"
			+ "## Examples of When to Use the Thinking Tool\n"
			+ "\n"
			+ "<example>\n"
			+ "User: Compare A and B and recommend one.\n"
			+ "Assistant: I will weigh evidence and tradeoffs before answering.\n"
			+ "*Calls thinking tool with pros/cons and final preference rationale.*\n"
			+ "Assistant: Here is my recommendation based on the evidence.\n"
			+ "<reasoning>\n"
			+ "The assistant used thinking because the task requires comparison and synthesis.\n"
			+ "</reasoning>\n"
			+ "</example>\n"
			+ "\n"
			+ "<example>\n"
			+ "User: What is in the KB about company policy?\n"
			+ "Assistant: I will first retrieve evidence.\n"
			+ "*Calls search_knowledge_keywords*\n"
			+ "Assistant: I should now assess whether the retrieval is sufficient or whether I need broader semantic search.\n"
			+ "*Calls thinking tool to evaluate evidence coverage and choose the next query.*\n"
			+ "<reasoning>\n"
			+ "The assistant used thinking after retrieval to evaluate evidence quality and decide the next retrieval step.\n"
			+ "</reasoning>\n"
			+ "</example>\n"
			+ "\n"
			+ "## Examples of When NOT to Use the Thinking Tool\n"
			+ "\n"
			+ "<example>\n"
			+ "User: Search the KB for document X.\n"
			+ "Assistant: I'll search the knowledge base now.\n"
			+ "<reasoning>\n"
			+ "The next step is a direct retrieval action, so thinking can wait until after evidence arrives.\n"
			+ "</reasoning>\n"
			+ "</example>\n";

	public ThinkingTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "thinking";
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

		ObjectNode thought = properties.putObject("thought");
		thought.put("type", "string");
		thought.put("description", "The reasoning or thought to share.");

		ObjectNode totalThoughts = properties.putObject("total_thoughts");
		totalThoughts.put("type", "integer");
		totalThoughts.put("description", "Total number of thoughts in this chain if known.");

		ObjectNode isRevision = properties.putObject("is_revision");
		isRevision.put("type", "boolean");
		isRevision.put("description", "Whether this thought revises a previous one.");

		ObjectNode round = properties.putObject("round");
		round.put("type", "integer");
		round.put("description", "Agent round or iteration index.");

		root.putArray("required").add("thought");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		String thought = arguments != null && arguments.hasNonNull("thought")
			? arguments.get("thought").asText()
			: "";
		return Mono.just(ToolResult.ok(thought));
	}
}
