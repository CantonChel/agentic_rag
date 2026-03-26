package com.agenticrag.app.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docreader")
public class DocreaderProperties {
	private String baseUrl = "http://localhost:8090";
	private String readPath = "/read";
	private String jobsPath = "/jobs";
	private String callbackBaseUrl = "http://localhost:8081";
	private String callbackSecret = "";
	private String signatureHeader = "X-Docreader-Signature";
	private String timestampHeader = "X-Docreader-Timestamp";
	private int callbackMaxSkewSeconds = 300;
	private int connectTimeoutMillis = 3000;
	private int readTimeoutMillis = 10000;

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getJobsPath() {
		return jobsPath;
	}

	public void setJobsPath(String jobsPath) {
		this.jobsPath = jobsPath;
	}

	public String getReadPath() {
		return readPath;
	}

	public void setReadPath(String readPath) {
		this.readPath = readPath;
	}

	public String getCallbackBaseUrl() {
		return callbackBaseUrl;
	}

	public void setCallbackBaseUrl(String callbackBaseUrl) {
		this.callbackBaseUrl = callbackBaseUrl;
	}

	public String getCallbackSecret() {
		return callbackSecret;
	}

	public void setCallbackSecret(String callbackSecret) {
		this.callbackSecret = callbackSecret;
	}

	public String getSignatureHeader() {
		return signatureHeader;
	}

	public void setSignatureHeader(String signatureHeader) {
		this.signatureHeader = signatureHeader;
	}

	public String getTimestampHeader() {
		return timestampHeader;
	}

	public void setTimestampHeader(String timestampHeader) {
		this.timestampHeader = timestampHeader;
	}

	public int getCallbackMaxSkewSeconds() {
		return callbackMaxSkewSeconds;
	}

	public void setCallbackMaxSkewSeconds(int callbackMaxSkewSeconds) {
		this.callbackMaxSkewSeconds = callbackMaxSkewSeconds;
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
}
