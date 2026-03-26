package com.agenticrag.app.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public class LlmStreamEvent {
	private final String type;
	private final String content;
	private final List<LlmToolCall> toolCalls;
	private final String finishReason;
	private final JsonNode raw;
	private final String source;
	private final String originModel;
	private final Integer roundId;
	private final String sessionId;
	private final String turnId;
	private final Long sequenceId;
	private final Long ts;
	private final String toolCallId;
	private final String toolName;
	private final String status;
	private final Long durationMs;
	private final JsonNode argsPreview;
	private final JsonNode resultPreview;
	private final String error;

	public LlmStreamEvent(String type, String content, List<LlmToolCall> toolCalls, String finishReason, JsonNode raw) {
		this(type, content, toolCalls, finishReason, raw, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public LlmStreamEvent(
		String type,
		String content,
		List<LlmToolCall> toolCalls,
		String finishReason,
		JsonNode raw,
		String source,
		String originModel,
		Integer roundId,
		String sessionId
	) {
		this(type, content, toolCalls, finishReason, raw, source, originModel, roundId, sessionId, null, null, null, null, null, null, null, null, null, null);
	}

	public LlmStreamEvent(
		String type,
		String content,
		List<LlmToolCall> toolCalls,
		String finishReason,
		JsonNode raw,
		String source,
		String originModel,
		Integer roundId,
		String sessionId,
		String turnId,
		Long sequenceId,
		Long ts,
		String toolCallId,
		String toolName,
		String status,
		Long durationMs,
		JsonNode argsPreview,
		JsonNode resultPreview,
		String error
	) {
		this.type = type;
		this.content = content;
		this.toolCalls = toolCalls;
		this.finishReason = finishReason;
		this.raw = raw;
		this.source = source;
		this.originModel = originModel;
		this.roundId = roundId;
		this.sessionId = sessionId;
		this.turnId = turnId;
		this.sequenceId = sequenceId;
		this.ts = ts;
		this.toolCallId = toolCallId;
		this.toolName = toolName;
		this.status = status;
		this.durationMs = durationMs;
		this.argsPreview = argsPreview;
		this.resultPreview = resultPreview;
		this.error = error;
	}

	public static LlmStreamEvent delta(String content) {
		return new LlmStreamEvent("delta", content, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static LlmStreamEvent done(String finishReason, List<LlmToolCall> toolCalls) {
		return new LlmStreamEvent("done", null, toolCalls, finishReason, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}
 
	public static LlmStreamEvent error(String message) {
		return new LlmStreamEvent("error", message, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static LlmStreamEvent thinking(String content, String source, String originModel, Integer roundId) {
		return new LlmStreamEvent("thinking", content, null, null, null, source, originModel, roundId, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static LlmStreamEvent sessionSwitched(String sessionId) {
		return new LlmStreamEvent("session_switched", null, null, null, null, null, null, null, sessionId, null, null, null, null, null, null, null, null, null, null);
	}

	public static LlmStreamEvent turnStart(String turnId, Long sequenceId, Long ts, String sessionId) {
		return new LlmStreamEvent(
			"turn_start",
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			sessionId,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public static LlmStreamEvent turnEnd(String turnId, Long sequenceId, Long ts, String finishReason, Integer roundId) {
		return new LlmStreamEvent(
			"turn_end",
			null,
			null,
			finishReason,
			null,
			null,
			null,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public static LlmStreamEvent toolStart(
		String turnId,
		Long sequenceId,
		Long ts,
		Integer roundId,
		String toolCallId,
		String toolName,
		JsonNode argsPreview
	) {
		return new LlmStreamEvent(
			"tool_start",
			null,
			null,
			null,
			null,
			null,
			null,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			toolCallId,
			toolName,
			"running",
			null,
			argsPreview,
			null,
			null
		);
	}

	public static LlmStreamEvent toolEnd(
		String turnId,
		Long sequenceId,
		Long ts,
		Integer roundId,
		String toolCallId,
		String toolName,
		String status,
		Long durationMs,
		JsonNode resultPreview,
		String error
	) {
		return new LlmStreamEvent(
			"tool_end",
			null,
			null,
			null,
			null,
			null,
			null,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			toolCallId,
			toolName,
			status,
			durationMs,
			null,
			resultPreview,
			error
		);
	}

	public static LlmStreamEvent delta(String content, String turnId, Long sequenceId, Long ts, Integer roundId) {
		return new LlmStreamEvent(
			"delta",
			content,
			null,
			null,
			null,
			null,
			null,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public static LlmStreamEvent done(String finishReason, List<LlmToolCall> toolCalls, String turnId, Long sequenceId, Long ts, Integer roundId) {
		return new LlmStreamEvent(
			"done",
			null,
			toolCalls,
			finishReason,
			null,
			null,
			null,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public static LlmStreamEvent error(String message, String turnId, Long sequenceId, Long ts, Integer roundId) {
		return new LlmStreamEvent(
			"error",
			message,
			null,
			null,
			null,
			null,
			null,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			message
		);
	}

	public static LlmStreamEvent thinking(
		String content,
		String source,
		String originModel,
		Integer roundId,
		String turnId,
		Long sequenceId,
		Long ts
	) {
		return new LlmStreamEvent(
			"thinking",
			content,
			null,
			null,
			null,
			source,
			originModel,
			roundId,
			null,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public static LlmStreamEvent sessionSwitched(String sessionId, String turnId, Long sequenceId, Long ts) {
		return new LlmStreamEvent(
			"session_switched",
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			sessionId,
			turnId,
			sequenceId,
			ts,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	public String getType() {
		return type;
	}

	public String getContent() {
		return content;
	}

	public List<LlmToolCall> getToolCalls() {
		return toolCalls;
	}

	public String getFinishReason() {
		return finishReason;
	}

	public JsonNode getRaw() {
		return raw;
	}

	public String getSource() {
		return source;
	}

	public String getOriginModel() {
		return originModel;
	}

	public Integer getRoundId() {
		return roundId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getTurnId() {
		return turnId;
	}

	public Long getSequenceId() {
		return sequenceId;
	}

	public Long getTs() {
		return ts;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public String getToolName() {
		return toolName;
	}

	public String getStatus() {
		return status;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public JsonNode getArgsPreview() {
		return argsPreview;
	}

	public JsonNode getResultPreview() {
		return resultPreview;
	}

	public String getError() {
		return error;
	}
}
