package com.agenticrag.app.tool;

public class ToolExecutionContext {
	private final String requestId;

	public ToolExecutionContext(String requestId) {
		this.requestId = requestId;
	}

	public String getRequestId() {
		return requestId;
	}
}
