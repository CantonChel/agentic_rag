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
	private static final String DESCRIPTION = """
Use this tool to perform reasoning, synthesis, evaluation, or correction. This tool is for THINKING only.

CRITICAL - Focus on Reasoning/Synthesis Only:
- Use this tool to combine evidence, resolve tradeoffs, or correct mistakes.
- DO NOT use this tool for retrieval or for planning tool usage.
- The output should be the reasoning content to show in thinking events.

## When to Use This Tool
Use this tool proactively in these scenarios:
1. You need to compare or evaluate options
2. You must synthesize multiple evidence sources
3. You need multi-step reasoning or correction
4. You are about to provide a non-trivial final response

## When NOT to Use This Tool
Skip this tool when:
1. The question is trivial and needs no reasoning
2. You only need to call a retrieval tool
3. You are just echoing a single source

## Examples of When to Use the Thinking Tool

<example>
User: Compare A and B and recommend one.
Assistant: I will weigh evidence and tradeoffs before answering.
*Calls thinking tool with pros/cons and final preference rationale.*
Assistant: Here is my recommendation based on the evidence.
<reasoning>
The assistant used thinking because the task requires comparison and synthesis.
</reasoning>
</example>

## Examples of When NOT to Use the Thinking Tool

<example>
User: Search the KB for document X.
Assistant: I'll search the knowledge base now.
<reasoning>
This is a retrieval action and does not require reasoning.
</reasoning>
</example>
""";

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
