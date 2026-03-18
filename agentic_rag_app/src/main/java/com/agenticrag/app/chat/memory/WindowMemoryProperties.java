package com.agenticrag.app.chat.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.memory.window")
public class WindowMemoryProperties {
	private int maxMessages = 40;

	public int getMaxMessages() {
		return maxMessages;
	}

	public void setMaxMessages(int maxMessages) {
		this.maxMessages = maxMessages;
	}
}

