package com.agenticrag.app.tool;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolDefinition {
	private final String name;
	private final String description;
	private final JsonNode parametersSchema;

	public ToolDefinition(String name, String description, JsonNode parametersSchema) {
		this.name = name;
		this.description = description;
		this.parametersSchema = parametersSchema;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public JsonNode getParametersSchema() {
		return parametersSchema;
	}
}
