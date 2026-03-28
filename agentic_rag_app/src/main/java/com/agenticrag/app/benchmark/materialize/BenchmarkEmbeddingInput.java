package com.agenticrag.app.benchmark.materialize;

public class BenchmarkEmbeddingInput {
	private final String knowledgeId;
	private final String chunkId;
	private final String content;

	public BenchmarkEmbeddingInput(String knowledgeId, String chunkId, String content) {
		this.knowledgeId = knowledgeId;
		this.chunkId = chunkId;
		this.content = content;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public String getChunkId() {
		return chunkId;
	}

	public String getContent() {
		return content;
	}
}
