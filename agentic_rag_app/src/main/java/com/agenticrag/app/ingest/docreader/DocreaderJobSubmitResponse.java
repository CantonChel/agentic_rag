package com.agenticrag.app.ingest.docreader;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DocreaderJobSubmitResponse {
	private boolean accepted;

	@JsonProperty("remote_job_id")
	private String remoteJobId;

	public boolean isAccepted() {
		return accepted;
	}

	public void setAccepted(boolean accepted) {
		this.accepted = accepted;
	}

	public String getRemoteJobId() {
		return remoteJobId;
	}

	public void setRemoteJobId(String remoteJobId) {
		this.remoteJobId = remoteJobId;
	}
}
