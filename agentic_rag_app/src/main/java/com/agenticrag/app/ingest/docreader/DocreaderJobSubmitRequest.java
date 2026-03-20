package com.agenticrag.app.ingest.docreader;

import java.util.Map;

public class DocreaderJobSubmitRequest {
	private String jobId;
	private String knowledgeId;
	private String fileUrl;
	private String callbackUrl;
	private String pipelineVersion;
	private Map<String, Object> options;

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public void setKnowledgeId(String knowledgeId) {
		this.knowledgeId = knowledgeId;
	}

	public String getFileUrl() {
		return fileUrl;
	}

	public void setFileUrl(String fileUrl) {
		this.fileUrl = fileUrl;
	}

	public String getCallbackUrl() {
		return callbackUrl;
	}

	public void setCallbackUrl(String callbackUrl) {
		this.callbackUrl = callbackUrl;
	}

	public String getPipelineVersion() {
		return pipelineVersion;
	}

	public void setPipelineVersion(String pipelineVersion) {
		this.pipelineVersion = pipelineVersion;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public void setOptions(Map<String, Object> options) {
		this.options = options;
	}
}
