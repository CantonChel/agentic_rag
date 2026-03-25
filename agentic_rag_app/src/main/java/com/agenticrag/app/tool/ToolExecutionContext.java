package com.agenticrag.app.tool;

public class ToolExecutionContext {
	private final String requestId;
	private final String userId;
	private final String sessionId;
	private final String traceId;

	public ToolExecutionContext(String requestId) {
		this(requestId, "anonymous", "default", "n/a");
	}

	public ToolExecutionContext(String requestId, String userId, String sessionId) {
		this(requestId, userId, sessionId, "n/a");
	}

	public ToolExecutionContext(String requestId, String userId, String sessionId, String traceId) {
		this.requestId = requestId;
		this.userId = userId == null || userId.trim().isEmpty() ? "anonymous" : userId.trim();
		this.sessionId = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
		this.traceId = traceId == null || traceId.trim().isEmpty() ? "n/a" : traceId.trim();
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

	public String getTraceId() {
		return traceId;
	}
}
