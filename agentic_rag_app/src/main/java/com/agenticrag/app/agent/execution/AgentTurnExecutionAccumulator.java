package com.agenticrag.app.agent.execution;

import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryWriteModel;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionToolCall;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnRetrievalTraceRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentTurnExecutionAccumulator {
	private final String turnId;
	private final String sessionId;
	private final String userId;
	private final String traceId;
	private final String provider;
	private final String buildId;
	private final String knowledgeBaseId;
	private final String kbScope;
	private final String evalMode;
	private final String thinkingProfile;
	private final boolean memoryEnabled;
	private final String userQuestion;
	private final Instant createdAt;
	private final Map<String, ToolCallState> toolCallsById = new LinkedHashMap<>();
	private final Set<String> retrievalTraceIds = new LinkedHashSet<>();
	private final Map<String, BenchmarkTurnRetrievalTraceRef> retrievalTraceRefsByKey = new LinkedHashMap<>();

	private String originModel;
	private String finalAnswer;
	private String finishReason;
	private Long latencyMs;
	private String errorMessage;
	private Instant completedAt;

	public AgentTurnExecutionAccumulator(
		String turnId,
		String sessionId,
		String userId,
		String traceId,
		String provider,
		String originModel,
		AgentExecutionControl control,
		String userQuestion,
		Instant createdAt
	) {
		this.turnId = normalize(turnId, "n/a");
		this.sessionId = normalize(sessionId, "default");
		this.userId = normalize(userId, "anonymous");
		this.traceId = normalizeNullable(traceId);
		this.provider = normalize(provider, "unknown");
		this.originModel = normalizeNullable(originModel);
		this.buildId = normalizeNullable(control != null ? control.getBuildId() : null);
		this.knowledgeBaseId = normalizeNullable(control != null ? control.getKnowledgeBaseId() : null);
		this.kbScope = control != null && control.getKbScope() != null ? control.getKbScope().name() : "AUTO";
		this.evalMode = control != null && control.getEvalMode() != null ? control.getEvalMode().name() : "DEFAULT";
		this.thinkingProfile = control != null && control.getThinkingProfile() != null ? control.getThinkingProfile().name() : "DEFAULT";
		this.memoryEnabled = control == null || control.isMemoryEnabled();
		this.userQuestion = normalize(userQuestion, "");
		this.createdAt = createdAt != null ? createdAt : Instant.now();
	}

	public synchronized void recordOriginModel(String originModel) {
		String normalized = normalizeNullable(originModel);
		if (normalized != null) {
			this.originModel = normalized;
		}
	}

	public synchronized void recordFinalAnswer(String finalAnswer) {
		String normalized = normalizeNullable(finalAnswer);
		if (normalized != null) {
			this.finalAnswer = normalized;
		}
	}

	public synchronized void recordToolResult(
		String toolCallId,
		String toolName,
		String status,
		Long durationMs,
		String error
	) {
		String normalizedToolCallId = normalize(toolCallId, "n/a");
		ToolCallState state = toolCallsById.computeIfAbsent(normalizedToolCallId, key -> new ToolCallState(normalizedToolCallId));
		state.toolName = normalize(toolName, "unknown");
		state.status = normalize(status, "unknown");
		state.durationMs = durationMs;
		state.error = normalizeNullable(error);
	}

	public synchronized void recordRetrievalSidecar(JsonNode sidecar, String fallbackToolCallId, String fallbackToolName) {
		if (sidecar == null || !sidecar.isObject()) {
			return;
		}
		String type = normalizeNullable(sidecar.path("type").asText(null));
		if (!"retrieval_context_v1".equals(type)) {
			return;
		}
		String sidecarTraceId = normalizeNullable(sidecar.path("traceId").asText(null));
		if (sidecarTraceId == null) {
			return;
		}
		String toolCallId = normalize(
			normalizeNullable(sidecar.path("toolCallId").asText(null)),
			normalize(fallbackToolCallId, "n/a")
		);
		String toolName = normalize(
			normalizeNullable(sidecar.path("toolName").asText(null)),
			normalize(fallbackToolName, "unknown")
		);
		retrievalTraceIds.add(sidecarTraceId);
		String key = sidecarTraceId + "::" + toolCallId + "::" + toolName;
		retrievalTraceRefsByKey.putIfAbsent(key, new BenchmarkTurnRetrievalTraceRef(sidecarTraceId, toolCallId, toolName));
	}

	public synchronized void markCompleted(String finishReason, Long latencyMs, String errorMessage, Instant completedAt) {
		this.finishReason = normalize(finishReason, "unknown");
		this.latencyMs = latencyMs;
		this.errorMessage = normalizeNullable(errorMessage);
		this.completedAt = completedAt != null ? completedAt : Instant.now();
	}

	public synchronized BenchmarkTurnExecutionSummaryWriteModel toWriteModel() {
		List<BenchmarkTurnExecutionToolCall> toolCalls = new ArrayList<>();
		for (ToolCallState state : toolCallsById.values()) {
			toolCalls.add(new BenchmarkTurnExecutionToolCall(
				state.toolCallId,
				state.toolName,
				state.status,
				state.durationMs,
				state.error
			));
		}
		return new BenchmarkTurnExecutionSummaryWriteModel(
			turnId,
			sessionId,
			userId,
			traceId,
			provider,
			originModel,
			buildId,
			knowledgeBaseId,
			kbScope,
			evalMode,
			thinkingProfile,
			memoryEnabled,
			userQuestion,
			finalAnswer,
			finishReason,
			latencyMs,
			toolCalls,
			new ArrayList<>(retrievalTraceIds),
			new ArrayList<>(retrievalTraceRefsByKey.values()),
			errorMessage,
			createdAt,
			completedAt != null ? completedAt : Instant.now()
		);
	}

	private String normalize(String value, String fallback) {
		String normalized = normalizeNullable(value);
		return normalized != null ? normalized : fallback;
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private static final class ToolCallState {
		private final String toolCallId;
		private String toolName = "unknown";
		private String status = "unknown";
		private Long durationMs;
		private String error;

		private ToolCallState(String toolCallId) {
			this.toolCallId = toolCallId;
		}
	}
}
