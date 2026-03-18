package com.agenticrag.app.tool;

public class ToolResult {
	private final boolean success;
	private final String output;
	private final String error;

	public ToolResult(boolean success, String output, String error) {
		this.success = success;
		this.output = output;
		this.error = error;
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

	public static ToolResult ok(String output) {
		return new ToolResult(true, output, null);
	}

	public static ToolResult error(String error) {
		return new ToolResult(false, null, error);
	}
}
