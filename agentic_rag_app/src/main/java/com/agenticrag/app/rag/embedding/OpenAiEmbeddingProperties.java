package com.agenticrag.app.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.embedding.openai")
public class OpenAiEmbeddingProperties {
	private String model = "text-embedding-3-small";
	private int batchSize = 64;

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

