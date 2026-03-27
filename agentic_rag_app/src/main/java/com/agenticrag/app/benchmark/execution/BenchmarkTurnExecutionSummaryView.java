package com.agenticrag.app.benchmark.execution;

import java.util.List;

public class BenchmarkTurnExecutionSummaryView {
	private final String turnId;
	private final String sessionId;
	private final String userId;
	private final String traceId;
	private final String provider;
	private final String originModel;
	private final String buildId;
	private final String knowledgeBaseId;
	private final String kbScope;
	private final String evalMode;
	private final String thinkingProfile;
	private final boolean memoryEnabled;
	private final String userQuestion;
	private final String finalAnswer;
	private final String finishReason;
	private final Long latencyMs;
	private final List<BenchmarkTurnExecutionToolCall> toolCalls;
	private final List<String> retrievalTraceIds;
	private final List<BenchmarkTurnRetrievalTraceRef> retrievalTraceRefs;
	private final String errorMessage;
	private final String createdAt;
	private final String completedAt;

	public BenchmarkTurnExecutionSummaryView(
		String turnId,
		String sessionId,
		String userId,
		String traceId,
		String provider,
		String originModel,
		String buildId,
		String knowledgeBaseId,
		String kbScope,
		String evalMode,
		String thinkingProfile,
		boolean memoryEnabled,
		String userQuestion,
		String finalAnswer,
		String finishReason,
		Long latencyMs,
		List<BenchmarkTurnExecutionToolCall> toolCalls,
		List<String> retrievalTraceIds,
		List<BenchmarkTurnRetrievalTraceRef> retrievalTraceRefs,
		String errorMessage,
		String createdAt,
		String completedAt
	) {
		this.turnId = turnId;
		this.sessionId = sessionId;
		this.userId = userId;
		this.traceId = traceId;
		this.provider = provider;
		this.originModel = originModel;
		this.buildId = buildId;
		this.knowledgeBaseId = knowledgeBaseId;
		this.kbScope = kbScope;
		this.evalMode = evalMode;
		this.thinkingProfile = thinkingProfile;
		this.memoryEnabled = memoryEnabled;
		this.userQuestion = userQuestion;
		this.finalAnswer = finalAnswer;
		this.finishReason = finishReason;
		this.latencyMs = latencyMs;
		this.toolCalls = toolCalls;
		this.retrievalTraceIds = retrievalTraceIds;
		this.retrievalTraceRefs = retrievalTraceRefs;
		this.errorMessage = errorMessage;
		this.createdAt = createdAt;
		this.completedAt = completedAt;
	}

	public String getTurnId() {
		return turnId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getUserId() {
		return userId;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getProvider() {
		return provider;
	}

	public String getOriginModel() {
		return originModel;
	}

	public String getBuildId() {
		return buildId;
	}

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public String getKbScope() {
		return kbScope;
	}

	public String getEvalMode() {
		return evalMode;
	}

	public String getThinkingProfile() {
		return thinkingProfile;
	}

	public boolean isMemoryEnabled() {
		return memoryEnabled;
	}

	public String getUserQuestion() {
		return userQuestion;
	}

	public String getFinalAnswer() {
		return finalAnswer;
	}

	public String getFinishReason() {
		return finishReason;
	}

	public Long getLatencyMs() {
		return latencyMs;
	}

	public List<BenchmarkTurnExecutionToolCall> getToolCalls() {
		return toolCalls;
	}

	public List<String> getRetrievalTraceIds() {
		return retrievalTraceIds;
	}

	public List<BenchmarkTurnRetrievalTraceRef> getRetrievalTraceRefs() {
		return retrievalTraceRefs;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public String getCompletedAt() {
		return completedAt;
	}
}
