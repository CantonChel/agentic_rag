package com.agenticrag.app.tool;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolResult {
	private final boolean success;
	private final String output;
	private final String error;
	private final JsonNode sidecar;

	public ToolResult(boolean success, String output, String error) {
		this(success, output, error, null);
	}

	public ToolResult(boolean success, String output, String error, JsonNode sidecar) {
		this.success = success;
		this.output = output;
		this.error = error;
		this.sidecar = sidecar;
	}

	public boolean isSuccess() {
		return success;
	}

	public String getOutput() {
		return output;
	}

	public String getError() {
		return error;
	}

	public JsonNode getSidecar() {
		return sidecar;
	}

	public static ToolResult ok(String output) {
		return ok(output, null);
	}

	public static ToolResult ok(String output, JsonNode sidecar) {
		return new ToolResult(true, output, null, sidecar);
	}

	public static ToolResult error(String error) {
		return error(error, null);
	}

	public static ToolResult error(String error, JsonNode sidecar) {
		return new ToolResult(false, null, error, sidecar);
	}
}
