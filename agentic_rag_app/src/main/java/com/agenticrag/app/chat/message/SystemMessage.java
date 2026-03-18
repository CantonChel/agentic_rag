package com.agenticrag.app.chat.message;

public class SystemMessage implements ChatMessage {
	private final String content;

	public SystemMessage(String content) {
		this.content = content;
	}

	@Override
	public ChatMessageType getType() {
		return ChatMessageType.SYSTEM;
	}

	@Override
	public String getContent() {
		return content;
	}
}

