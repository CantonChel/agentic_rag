package com.agenticrag.app.benchmark.retrieval;

public enum RetrievalTraceRecordType {
	CHUNK("chunk"),
	STAGE_SUMMARY("stage_summary");

	private final String value;

	RetrievalTraceRecordType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}
}
