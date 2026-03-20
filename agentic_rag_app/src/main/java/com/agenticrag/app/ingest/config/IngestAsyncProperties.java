package com.agenticrag.app.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest.async")
public class IngestAsyncProperties {
	private boolean enabled = true;
	private String pipelineVersion = "v1";
	private int maxRetry = 5;
	private int pollTimeoutSeconds = 2;
	private int leaseSeconds = 180;
	private int retryReplayBatchSize = 100;
	private int workerCorePoolSize = 4;
	private int workerMaxPoolSize = 16;
	private int workerQueueCapacity = 1000;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getPipelineVersion() {
		return pipelineVersion;
	}

	public void setPipelineVersion(String pipelineVersion) {
		this.pipelineVersion = pipelineVersion;
	}

	public int getMaxRetry() {
		return maxRetry;
	}

	public void setMaxRetry(int maxRetry) {
		this.maxRetry = maxRetry;
	}

	public int getPollTimeoutSeconds() {
		return pollTimeoutSeconds;
	}

	public void setPollTimeoutSeconds(int pollTimeoutSeconds) {
		this.pollTimeoutSeconds = pollTimeoutSeconds;
	}

	public int getLeaseSeconds() {
		return leaseSeconds;
	}

	public void setLeaseSeconds(int leaseSeconds) {
		this.leaseSeconds = leaseSeconds;
	}

	public int getRetryReplayBatchSize() {
		return retryReplayBatchSize;
	}

	public void setRetryReplayBatchSize(int retryReplayBatchSize) {
		this.retryReplayBatchSize = retryReplayBatchSize;
	}

	public int getWorkerCorePoolSize() {
		return workerCorePoolSize;
	}

	public void setWorkerCorePoolSize(int workerCorePoolSize) {
		this.workerCorePoolSize = workerCorePoolSize;
	}

	public int getWorkerMaxPoolSize() {
		return workerMaxPoolSize;
	}

	public void setWorkerMaxPoolSize(int workerMaxPoolSize) {
		this.workerMaxPoolSize = workerMaxPoolSize;
	}

	public int getWorkerQueueCapacity() {
		return workerQueueCapacity;
	}

	public void setWorkerQueueCapacity(int workerQueueCapacity) {
		this.workerQueueCapacity = workerQueueCapacity;
	}
}
