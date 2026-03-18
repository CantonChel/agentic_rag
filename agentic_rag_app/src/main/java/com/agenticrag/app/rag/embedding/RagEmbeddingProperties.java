package com.agenticrag.app.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.embedding")
public class RagEmbeddingProperties {
	private String provider = "openai";

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}
}

