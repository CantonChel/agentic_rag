package com.agenticrag.app.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.embedding.siliconflow")
public class SiliconFlowEmbeddingProperties {
	private String baseUrl = "https://api.siliconflow.cn/v1";
	private String apiKey = "";
	private String model = "BAAI/bge-large-zh-v1.5";
	private int batchSize = 64;

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}
}

