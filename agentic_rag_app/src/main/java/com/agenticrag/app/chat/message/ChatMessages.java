package com.agenticrag.app.chat.message;

import com.agenticrag.app.llm.LlmToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatMessages {
	private final List<ChatMessage> items = new ArrayList<>();

	public ChatMessages addSystem(String content) {
		items.add(new SystemMessage(content));
		return this;
	}

	public ChatMessages addUser(String content) {
		items.add(new UserMessage(content));
		return this;
	}

	public ChatMessages addAssistant(String content) {
		items.add(new AssistantMessage(content));
		return this;
	}

	public ChatMessages addToolCalls(List<LlmToolCall> toolCalls) {
		items.add(new ToolCallMessage(toolCalls));
		return this;
	}

	public ChatMessages addToolResult(String toolName, String toolCallId, boolean success, String output, String error) {
		items.add(new ToolResultMessage(toolName, toolCallId, success, output, error));
		return this;
	}

	public List<ChatMessage> items() {
		return Collections.unmodifiableList(items);
	}
}

