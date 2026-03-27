package com.agenticrag.app.benchmark.execution;

public record BenchmarkTurnExecutionToolCall(
	String toolCallId,
	String toolName,
	String status,
	Long durationMs,
	String error
) {
}
