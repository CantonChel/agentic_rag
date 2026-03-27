package com.agenticrag.app.rag.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TextChunkMetadataHelper {
	private TextChunkMetadataHelper() {
	}

	public static TextChunk withAdditionalMetadata(TextChunk chunk, Map<String, Object> additions) {
		if (chunk == null || additions == null || additions.isEmpty()) {
			return chunk;
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (chunk.getMetadata() != null) {
			metadata.putAll(chunk.getMetadata());
		}
		for (Map.Entry<String, Object> entry : additions.entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			metadata.put(entry.getKey(), entry.getValue());
		}
		return new TextChunk(chunk.getChunkId(), chunk.getDocumentId(), chunk.getText(), chunk.getEmbedding(), metadata);
	}

	public static TextChunk withRetrievalScore(TextChunk chunk, Double score) {
		if (chunk == null || score == null) {
			return chunk;
		}
		Map<String, Object> additions = new LinkedHashMap<>();
		additions.put("retrieval_score", score);
		return withAdditionalMetadata(chunk, additions);
	}

	public static Double retrievalScore(TextChunk chunk) {
		if (chunk == null || chunk.getMetadata() == null) {
			return null;
		}
		Object value = chunk.getMetadata().get("retrieval_score");
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Double.valueOf(String.valueOf(value));
		} catch (Exception ignored) {
			return null;
		}
	}

	public static String metadataText(TextChunk chunk, String key) {
		if (chunk == null || key == null || key.trim().isEmpty() || chunk.getMetadata() == null) {
			return null;
		}
		Object value = chunk.getMetadata().get(key);
		if (value == null) {
			return null;
		}
		String normalized = String.valueOf(value).trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
