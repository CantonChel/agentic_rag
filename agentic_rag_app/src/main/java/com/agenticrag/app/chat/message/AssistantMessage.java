package com.agenticrag.app.chat.message;

public class AssistantMessage implements ChatMessage {
	private final String content;

	public AssistantMessage(String content) {
		this.content = content;
	}

	@Override
	public ChatMessageType getType() {
		return ChatMessageType.ASSISTANT;
	}

	@Override
	public String getContent() {
		return content;
	}
}

