package com.agenticrag.app.llm;

import com.fasterxml.jackson.databind.JsonNode;

public class LlmToolCall {
	private final String id;
	private final String name;
	private final JsonNode arguments;

	public LlmToolCall(String id, String name, JsonNode arguments) {
		this.id = id;
		this.name = name;
		this.arguments = arguments;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public JsonNode getArguments() {
		return arguments;
	}
}

