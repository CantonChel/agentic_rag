package com.agenticrag.app.ingest.queue;

public class ReservedJob {
	private final String jobId;
	private final String payload;

	public ReservedJob(String jobId, String payload) {
		this.jobId = jobId;
		this.payload = payload;
	}

	public String getJobId() {
		return jobId;
	}

	public String getPayload() {
		return payload;
	}
}
