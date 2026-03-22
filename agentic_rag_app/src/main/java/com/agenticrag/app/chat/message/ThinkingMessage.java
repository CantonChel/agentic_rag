package com.agenticrag.app.chat.message;

public class ThinkingMessage implements ChatMessage {
	private final String content;

	public ThinkingMessage(String content) {
		this.content = content;
	}

	@Override
	public ChatMessageType getType() {
		return ChatMessageType.THINKING;
	}

	@Override
	public String getContent() {
		return content;
	}
}
