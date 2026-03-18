package com.agenticrag.app.rag.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TextChunk {
	private final String chunkId;
	private final String documentId;
	private final String text;
	private List<Double> embedding;
	private final Map<String, Object> metadata;

	public TextChunk(String chunkId, String documentId, String text, List<Double> embedding, Map<String, Object> metadata) {
		this.chunkId = chunkId != null && !chunkId.trim().isEmpty() ? chunkId : UUID.randomUUID().toString();
		this.documentId = documentId;
		this.text = text != null ? text : "";
		this.embedding = embedding;
		if (metadata == null) {
			this.metadata = Collections.emptyMap();
		} else {
			this.metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
		}
	}

	public String getChunkId() {
		return chunkId;
	}

	public String getDocumentId() {
		return documentId;
	}

	public String getText() {
		return text;
	}

	public List<Double> getEmbedding() {
		return embedding;
	}

	public void setEmbedding(List<Double> embedding) {
		this.embedding = embedding;
	}

	public Map<String, Object> getMetadata() {
		return metadata;
	}
}

