package com.agenticrag.app.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docreader")
public class DocreaderProperties {
	private String baseUrl = "http://localhost:8090";
	private String readPath = "/read";
	private int connectTimeoutMillis = 3000;
	private int readTimeoutMillis = 10000;
	private int maxInMemorySizeBytes = 10 * 1024 * 1024;

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getReadPath() {
		return readPath;
	}

	public void setReadPath(String readPath) {
		this.readPath = readPath;
	}

	public int getConnectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	public void setConnectTimeoutMillis(int connectTimeoutMillis) {
		this.connectTimeoutMillis = connectTimeoutMillis;
	}

	public int getReadTimeoutMillis() {
		return readTimeoutMillis;
	}

	public void setReadTimeoutMillis(int readTimeoutMillis) {
		this.readTimeoutMillis = readTimeoutMillis;
	}

	public int getMaxInMemorySizeBytes() {
		return maxInMemorySizeBytes;
	}

	public void setMaxInMemorySizeBytes(int maxInMemorySizeBytes) {
		this.maxInMemorySizeBytes = maxInMemorySizeBytes;
	}
}
