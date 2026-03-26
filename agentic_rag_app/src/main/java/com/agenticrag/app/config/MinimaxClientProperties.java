package com.agenticrag.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm.minimax")
public class MinimaxClientProperties {
	private String apiKey;
	private String baseUrl;
	private String model;
	private boolean reasoningSplit = true;

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public boolean isReasoningSplit() {
		return reasoningSplit;
	}

	public void setReasoningSplit(boolean reasoningSplit) {
		this.reasoningSplit = reasoningSplit;
	}
}
