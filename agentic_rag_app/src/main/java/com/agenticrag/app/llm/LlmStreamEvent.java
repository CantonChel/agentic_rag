package com.agenticrag.app.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public class LlmStreamEvent {
	private final String type;
	private final String content;
	private final List<LlmToolCall> toolCalls;
	private final String finishReason;
	private final JsonNode raw;

	public LlmStreamEvent(String type, String content, List<LlmToolCall> toolCalls, String finishReason, JsonNode raw) {
		this.type = type;
		this.content = content;
		this.toolCalls = toolCalls;
		this.finishReason = finishReason;
		this.raw = raw;
	}

	public static LlmStreamEvent delta(String content) {
		return new LlmStreamEvent("delta", content, null, null, null);
	}

	public static LlmStreamEvent done(String finishReason, List<LlmToolCall> toolCalls) {
		return new LlmStreamEvent("done", null, toolCalls, finishReason, null);
	}
 
	public static LlmStreamEvent error(String message) {
		return new LlmStreamEvent("error", message, null, null, null);
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
}
