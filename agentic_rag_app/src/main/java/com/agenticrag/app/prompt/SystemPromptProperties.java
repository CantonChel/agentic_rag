package com.agenticrag.app.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompt.system")
public class SystemPromptProperties {
	private String base;
	private String agentBase;

	public String getBase() {
		return base;
	}

	public void setBase(String base) {
		this.base = base;
	}

	public String getAgentBase() {
		return agentBase;
	}

	public void setAgentBase(String agentBase) {
		this.agentBase = agentBase;
	}
}
