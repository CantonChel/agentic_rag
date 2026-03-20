package com.agenticrag.app.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest.queue.redis")
public class RedisQueueProperties {
	private String readyKey = "rag:docparse:ready";
	private String processingKey = "rag:docparse:processing";
	private String retryKey = "rag:docparse:retry";
	private String dlqKey = "rag:docparse:dlq";

	public String getReadyKey() {
		return readyKey;
	}

	public void setReadyKey(String readyKey) {
		this.readyKey = readyKey;
	}

	public String getProcessingKey() {
		return processingKey;
	}

	public void setProcessingKey(String processingKey) {
		this.processingKey = processingKey;
	}

	public String getRetryKey() {
		return retryKey;
	}

	public void setRetryKey(String retryKey) {
		this.retryKey = retryKey;
	}

	public String getDlqKey() {
		return dlqKey;
	}

	public void setDlqKey(String dlqKey) {
		this.dlqKey = dlqKey;
	}
}
