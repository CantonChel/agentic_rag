package com.agenticrag.app.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
	private int maxIterations = 6;
	private long toolTimeoutSeconds = 30;

	public int getMaxIterations() {
		return maxIterations;
	}

	public void setMaxIterations(int maxIterations) {
		this.maxIterations = maxIterations;
	}

	public long getToolTimeoutSeconds() {
		return toolTimeoutSeconds;
	}

	public void setToolTimeoutSeconds(long toolTimeoutSeconds) {
		this.toolTimeoutSeconds = toolTimeoutSeconds;
	}
}

