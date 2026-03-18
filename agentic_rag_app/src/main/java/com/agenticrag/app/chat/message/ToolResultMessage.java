package com.agenticrag.app.chat.message;

public class ToolResultMessage implements ChatMessage {
	private final String toolName;
	private final String toolCallId;
	private final boolean success;
	private final String output;
	private final String error;

	public ToolResultMessage(String toolName, String toolCallId, boolean success, String output, String error) {
		this.toolName = toolName;
		this.toolCallId = toolCallId;
		this.success = success;
		this.output = output;
		this.error = error;
	}

	@Override
	public ChatMessageType getType() {
		return ChatMessageType.TOOL_RESULT;
	}

	@Override
	public String getContent() {
		if (success) {
			return output;
		}
		return error;
	}

	public String getToolName() {
		return toolName;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public boolean isSuccess() {
		return success;
	}

	public String getOutput() {
		return output;
	}

	public String getError() {
		return error;
	}
}

