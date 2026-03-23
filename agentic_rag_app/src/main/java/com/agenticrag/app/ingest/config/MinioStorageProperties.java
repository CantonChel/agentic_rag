package com.agenticrag.app.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest.minio")
public class MinioStorageProperties {
	private String endpoint = "http://localhost:9000";
	private String accessKey = "minioadmin";
	private String secretKey = "minioadmin";
	private String bucket = "agentic-rag";
	private boolean secure = false;
	private int presignExpirySeconds = 3600;

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	public int getPresignExpirySeconds() {
		return presignExpirySeconds;
	}

	public void setPresignExpirySeconds(int presignExpirySeconds) {
		this.presignExpirySeconds = presignExpirySeconds;
	}
}
