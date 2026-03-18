package com.agenticrag.app.chat.message;

import com.agenticrag.app.llm.LlmToolCall;
import java.util.List;

public class ToolCallMessage implements ChatMessage {
	private final List<LlmToolCall> toolCalls;

	public ToolCallMessage(List<LlmToolCall> toolCalls) {
		this.toolCalls = toolCalls;
	}

	@Override
	public ChatMessageType getType() {
		return ChatMessageType.TOOL_CALL;
	}

	@Override
	public String getContent() {
		return null;
	}

	public List<LlmToolCall> getToolCalls() {
		return toolCalls;
	}
}

