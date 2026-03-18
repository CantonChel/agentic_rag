package com.agenticrag.app.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompt.system")
public class SystemPromptProperties {
	private String base;

	public String getBase() {
		return base;
	}

	public void setBase(String base) {
		this.base = base;
	}
}

