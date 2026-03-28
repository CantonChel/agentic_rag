package com.agenticrag.app.benchmark.retrieval;

import java.util.Locale;

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

	@Override
	public String toString() {
		return value;
	}

	public static RetrievalTraceRecordType fromValue(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (RetrievalTraceRecordType recordType : values()) {
			if (recordType.value.equals(normalized)) {
				return recordType;
			}
		}
		return null;
	}
}
