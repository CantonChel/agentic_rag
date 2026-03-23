package com.agenticrag.app.tool;

public class ToolExecutionContext {
	private final String requestId;
	private final String userId;
	private final String sessionId;

	public ToolExecutionContext(String requestId) {
		this(requestId, "anonymous", "default");
	}

	public ToolExecutionContext(String requestId, String userId, String sessionId) {
		this.requestId = requestId;
		this.userId = userId == null || userId.trim().isEmpty() ? "anonymous" : userId.trim();
		this.sessionId = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
	}

	public String getRequestId() {
		return requestId;
	}

	public String getUserId() {
		return userId;
	}

	public String getSessionId() {
		return sessionId;
	}
}
