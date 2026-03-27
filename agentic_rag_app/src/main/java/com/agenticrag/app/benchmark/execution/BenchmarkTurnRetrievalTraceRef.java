package com.agenticrag.app.benchmark.execution;

public record BenchmarkTurnRetrievalTraceRef(
	String traceId,
	String toolCallId,
	String toolName
) {
}
