package com.agenticrag.app.ingest.model;

public enum ParseJobStatus {
	PENDING,
	QUEUED,
	DISPATCHED,
	PARSING,
	INDEXING,
	RETRY_WAIT,
	SUCCESS,
	FAILED,
	DEAD_LETTER
}
