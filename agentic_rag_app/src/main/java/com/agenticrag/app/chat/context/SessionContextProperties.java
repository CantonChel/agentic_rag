package com.agenticrag.app.chat.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "context.session")
public class SessionContextProperties {
	private int maxTokens = 20000;
	private int maxBytes = 0;
	private int keepLastMessages = 20;

	public int getMaxTokens() {
		return maxTokens;
	}

	public void setMaxTokens(int maxTokens) {
		this.maxTokens = maxTokens;
	}

	public int getMaxBytes() {
		return maxBytes;
	}

	public void setMaxBytes(int maxBytes) {
		this.maxBytes = maxBytes;
	}

	public int getKeepLastMessages() {
		return keepLastMessages;
	}

	public void setKeepLastMessages(int keepLastMessages) {
		this.keepLastMessages = keepLastMessages;
	}
}
