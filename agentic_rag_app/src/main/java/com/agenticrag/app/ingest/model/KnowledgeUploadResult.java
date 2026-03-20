package com.agenticrag.app.ingest.model;

public class KnowledgeUploadResult {
	private final String knowledgeId;
	private final String jobId;
	private final ParseJobStatus status;

	public KnowledgeUploadResult(String knowledgeId, String jobId, ParseJobStatus status) {
		this.knowledgeId = knowledgeId;
		this.jobId = jobId;
		this.status = status;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public String getJobId() {
		return jobId;
	}

	public ParseJobStatus getStatus() {
		return status;
	}
}
