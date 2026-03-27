package com.agenticrag.app.benchmark.execution;

import java.time.Instant;
import java.util.List;

public record BenchmarkTurnExecutionSummaryWriteModel(
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
	Instant createdAt,
	Instant completedAt
) {
}
