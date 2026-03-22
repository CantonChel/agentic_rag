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

	public ThinkingTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "thinking";
	}

	@Override
	public String description() {
		return "Generate structured reasoning steps for the current subtask.";
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
