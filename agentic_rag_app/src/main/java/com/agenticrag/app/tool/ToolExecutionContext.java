package com.agenticrag.app.tool;

import java.util.UUID;

public class ToolExecutionContext {
	private final String requestId;
	private final String userId;
	private final String sessionId;
	private final String traceId;
	private final String knowledgeBaseId;
	private final String toolCallId;

	public ToolExecutionContext(String requestId) {
		this(requestId, "anonymous", "default", "n/a", null, null);
	}

	public ToolExecutionContext(String requestId, String userId, String sessionId) {
		this(requestId, userId, sessionId, "n/a", null, null);
	}

	public ToolExecutionContext(String requestId, String userId, String sessionId, String traceId) {
		this(requestId, userId, sessionId, traceId, null, null);
	}

	public ToolExecutionContext(String requestId, String userId, String sessionId, String traceId, String knowledgeBaseId) {
		this(requestId, userId, sessionId, traceId, knowledgeBaseId, null);
	}

	public ToolExecutionContext(
		String requestId,
		String userId,
		String sessionId,
		String traceId,
		String knowledgeBaseId,
		String toolCallId
	) {
		this.requestId = normalizeRequestId(requestId);
		this.userId = userId == null || userId.trim().isEmpty() ? "anonymous" : userId.trim();
		this.sessionId = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
		this.traceId = traceId == null || traceId.trim().isEmpty() ? "n/a" : traceId.trim();
		this.knowledgeBaseId = knowledgeBaseId == null || knowledgeBaseId.trim().isEmpty() ? null : knowledgeBaseId.trim();
		this.toolCallId = toolCallId == null || toolCallId.trim().isEmpty() ? this.requestId : toolCallId.trim();
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

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	private String normalizeRequestId(String requestId) {
		if (requestId == null || requestId.trim().isEmpty()) {
			return UUID.randomUUID().toString();
		}
		return requestId.trim();
	}
}
