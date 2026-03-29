package com.agenticrag.app.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.turn")
public class AgentTurnContextProperties {
	private int contextWindowTokens = 0;
	private int reserveTokens = 16000;
	private int keepRecentTokens = 16000;

	public int getContextWindowTokens() {
		return contextWindowTokens;
	}

	public void setContextWindowTokens(int contextWindowTokens) {
		this.contextWindowTokens = contextWindowTokens;
	}

	public int getReserveTokens() {
		return reserveTokens;
	}

	public void setReserveTokens(int reserveTokens) {
		this.reserveTokens = reserveTokens;
	}

	public int getKeepRecentTokens() {
		return keepRecentTokens;
	}

	public void setKeepRecentTokens(int keepRecentTokens) {
		this.keepRecentTokens = keepRecentTokens;
	}
}
