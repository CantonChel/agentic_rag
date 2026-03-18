package com.agenticrag.app.chat.message;

public class UserMessage implements ChatMessage {
	private final String content;

	public UserMessage(String content) {
		this.content = content;
	}

	@Override
	public ChatMessageType getType() {
		return ChatMessageType.USER;
	}

	@Override
	public String getContent() {
		return content;
	}
}

