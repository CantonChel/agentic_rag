package com.agenticrag.app.tool;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

public interface Tool {
	String name();

	String description();

	JsonNode parametersSchema();

	Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context);
}

