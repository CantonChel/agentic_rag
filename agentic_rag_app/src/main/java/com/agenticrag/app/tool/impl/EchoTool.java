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
public class EchoTool implements Tool {
	private final ObjectMapper objectMapper;

	public EchoTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "echo";
	}

	@Override
	public String description() {
		return "Echo the input text.";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");
		ObjectNode text = properties.putObject("text");
		text.put("type", "string");
		root.putArray("required").add("text");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		String text = arguments != null && arguments.hasNonNull("text") ? arguments.get("text").asText() : "";
		return Mono.just(ToolResult.ok(text));
	}
}

