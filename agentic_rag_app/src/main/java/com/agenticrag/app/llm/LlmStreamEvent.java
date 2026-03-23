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

	public LlmStreamEvent(String type, String content, List<LlmToolCall> toolCalls, String finishReason, JsonNode raw) {
		this(type, content, toolCalls, finishReason, raw, null, null, null, null);
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
		this.type = type;
		this.content = content;
		this.toolCalls = toolCalls;
		this.finishReason = finishReason;
		this.raw = raw;
		this.source = source;
		this.originModel = originModel;
		this.roundId = roundId;
		this.sessionId = sessionId;
	}

	public static LlmStreamEvent delta(String content) {
		return new LlmStreamEvent("delta", content, null, null, null, null, null, null, null);
	}

	public static LlmStreamEvent done(String finishReason, List<LlmToolCall> toolCalls) {
		return new LlmStreamEvent("done", null, toolCalls, finishReason, null, null, null, null, null);
	}
 
	public static LlmStreamEvent error(String message) {
		return new LlmStreamEvent("error", message, null, null, null, null, null, null, null);
	}

	public static LlmStreamEvent thinking(String content, String source, String originModel, Integer roundId) {
		return new LlmStreamEvent("thinking", content, null, null, null, source, originModel, roundId, null);
	}

	public static LlmStreamEvent sessionSwitched(String sessionId) {
		return new LlmStreamEvent("session_switched", null, null, null, null, null, null, null, sessionId);
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
}
