package com.agenticrag.app.benchmark.retrieval;

import java.util.Locale;

public enum RetrievalTraceStage {
	KEYWORD_LIKE("keyword_like"),
	BM25("bm25"),
	DENSE("dense"),
	HYBRID_FUSED("hybrid_fused"),
	RERANKED("reranked"),
	CONTEXT_OUTPUT("context_output");

	private final String value;

	RetrievalTraceStage(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	@Override
	public String toString() {
		return value;
	}

	public static RetrievalTraceStage fromValue(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (RetrievalTraceStage stage : values()) {
			if (stage.value.equals(normalized)) {
				return stage;
			}
		}
		return null;
	}
}
